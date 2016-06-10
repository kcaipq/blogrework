package eventstore
package fake

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.joda.time.DateTimeUtils
import scala.concurrent.stm._

object FakeEventStore {
  def fromHistory[StreamId, Event](events: Seq[Event])(implicit descriptor: EventStreamType[StreamId, Event]): FakeEventStore[Event] = {
    val result = new FakeEventStore[Event]
    for (event <- events) {
      val expected = result.reader.streamRevision(descriptor.streamId(event))
      result.committer.tryCommit(Changes(expected, event))
    }
    result
  }
}
class FakeEventStore[Event] extends EventStore[Event] {

  private[this] val executor = Executors.newCachedThreadPool
  private[this] val closed = Ref(false).single
  private[this] val commits = Ref(Vector.empty[Commit[Event]]).single
  private[this] val streams = Ref(Map.empty[Any, Vector[Commit[Event]]]).single

  override def toString = s"FakeEventStore(${reader.storeRevision})"

  override object reader extends CommitReader[Event] {
    override def storeRevision = StoreRevision(commits().size)

    override def readCommits[E <: Event: Manifest](since: StoreRevision, to: StoreRevision): Stream[Commit[E]] = {
      commits().slice((since.value min Int.MaxValue).toInt, (to.value min Int.MaxValue).toInt).toStream.map(_.withOnlyEventsOfType[E])
    }

    override def streamRevision[StreamId, Event](streamId: StreamId)(implicit descriptor: EventStreamType[StreamId, Event]): StreamRevision = StreamRevision(streams().get(streamId).map(_.size.toLong).getOrElse(0L))

    override def readStream[StreamId, E <: Event](streamId: StreamId, since: StreamRevision = StreamRevision.Initial, to: StreamRevision = StreamRevision.Maximum)(implicit streamType: EventStreamType[StreamId, E]): Stream[Commit[E]] = {
      streams().getOrElse(streamId, Vector.empty).
        slice((since.value min Int.MaxValue).toInt, (to.value min Int.MaxValue).toInt).
        toStream.
        map(commit => commit.copy(events = commit.events.map(streamType.cast)))
    }
  }

  override object committer extends EventCommitter[Event] {
    import reader._

    override def tryCommit[E <: Event](changes: Changes[E]): CommitResult[E] = {
      require(Txn.findCurrent.isEmpty, "the fake event store cannot participate in an STM transaction, just like a real event store")

      implicit val descriptor = changes.eventStreamType
      val streamId = descriptor.toString(changes.streamId)

      atomic { implicit txn =>
        val actual = streamRevision(changes.streamId)

        if (changes.expected < actual) {
          val conflicting = readStream(changes.streamId, since = changes.expected)
          Left(Conflict(conflicting.flatMap(_.committedEvents)))
        } else if (changes.expected > actual) {
          throw new IllegalArgumentException("expected revision %d greater than actual revision %d" format (changes.expected.value, actual.value))
        } else {
          val commit = Commit(storeRevision.next, DateTimeUtils.currentTimeMillis, streamId, actual.next, changes.events, changes.headers)
          commits.transform(_ :+ commit)
          streams.transform(streams => streams.updated(changes.streamId, streams.getOrElse(changes.streamId, Vector.empty) :+ commit))
          Right(commit)
        }
      }
    }
  }

  override object publisher extends CommitPublisher[Event] {
    override def subscribe[E <: Event: Manifest](since: StoreRevision)(listener: CommitListener[E]): Subscription = {
      val cancelled = Ref(false).single
      val last = Ref(since).single

      executor.execute(new Runnable {
        @annotation.tailrec override def run {
          val pending = atomic { implicit txn =>
            if (closed() || cancelled()) None else {
              val pending = commits().drop(last().value.toInt)
              if (pending.isEmpty) retry
              else Some(pending)
            }
          }
          pending match {
            case None => // Stop.
            case Some(commits) =>
              commits.foreach { commit =>
                listener(commit.withOnlyEventsOfType[E])
                last() = commit.storeRevision
              }
              run
          }
        }
      })

      new Subscription {
        override def cancel() = cancelled.set(true)
        override def toString = s"Subscription(${last()}, ${cancelled()}, ${FakeEventStore.this})"
      }
    }
  }

  def close(): Unit = {
    closed() = true
    executor.shutdown
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
      executor.shutdownNow
      ()
    }
  }
}
