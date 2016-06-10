package eventstore
package redis

import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.apache.commons.pool.impl.GenericObjectPool.Config
import org.joda.time.DateTimeUtils
import play.api.Logger
import play.api.libs.json._
import _root_.redis.clients.jedis._
import scala.collection.JavaConverters._
import scala.concurrent._

/**
 * The Redis event store implementation uses two primary data structures:
 *
 * - A hash containing all commits indexed by the store revision (commit id).
 * - A list of commit ids per event stream.
 *
 * Events must have an associated `Format` instance to allow for (de)serialization to JSON.
 */
class RedisEventStore[Event: Format](val name: String)(implicit val jedisPool: JedisPool) extends EventStore[Event] with messaging.redis.RedisSupport {

  // Redis key for the commits hash. Also used as commits publication channel for subscribers.
  protected[this] val CommitsKey: String = s"$name:commits"

  // Redis key for the list of commit ids per event stream.
  protected[this] def keyForStream(streamId: String): String = s"$name:stream:$streamId"

  // Executor for event store subscribers. Each subscriber gets its own thread.
  private[this] implicit val executionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool)

  // Subscriber control channel for handling event store closing and subscription cancellation.
  private[this] val ControlChannel: String = s"$name:control"

  private[this] val DispatchQueuesKey: String = s"$name:dispatchQueues"

  // Control message used to notify subscribers the event store is closing.
  private[this] val CloseToken = UUID.randomUUID.toString
  @volatile private[this] var closed = false

  // Maximum number of commits to read at one time.
  private[this] val ChunkSize = 10000

  // Reads and deserializes commits in `ChunkSize` chunks.
  private[this] def doReadCommits(commitIds: Seq[String]): Stream[Commit[Event]] = {
    val chunks = commitIds.grouped(ChunkSize).map(_.toArray)
    chunks.flatMap { chunk =>
      val serializedCommits = withJedis { _.hmget(CommitsKey, chunk: _*) }
      serializedCommits.asScala.par.map(deserializeCommit)
    }.toStream
  }

  /**
   * Adds a queue to push commit ids to when events are committed.
   */
  def addDispatchQueue(name: String): Unit = {
    withJedis { _.sadd(DispatchQueuesKey, name) }
    ()
  }

  def removeDispatchQueue(name: String): Unit = {
    withJedis { _.srem(DispatchQueuesKey, name) }
    ()
  }

  // Helpers to serialize and deserialize commits.
  protected[this] def deserializeCommit(serialized: String): Commit[Event] = Json.fromJson[Commit[Event]](Json.parse(serialized)).get
  protected[this] def serializeCommit(commit: Commit[Event]): String = Json.stringify(Json.toJson(commit))

  override object reader extends CommitReader[Event] {
    override def storeRevision: StoreRevision = withJedis { jedis => StoreRevision(jedis.hlen(CommitsKey)) }

    override def readCommits[E <: Event: Manifest](since: StoreRevision, to: StoreRevision): Stream[Commit[E]] = {
      val current = storeRevision
      if (since >= current) Stream.empty else {
        val revisionRange = (since.value + 1) to (to.value min current.value)
        doReadCommits(revisionRange.map(_.toString)).map(_.withOnlyEventsOfType[E])
      }
    }

    override def streamRevision[StreamId, Event](streamId: StreamId)(implicit descriptor: EventStreamType[StreamId, Event]): StreamRevision = withJedis { jedis =>
      StreamRevision(jedis.llen(keyForStream(descriptor.toString(streamId))))
    }

    override def readStream[StreamId, E <: Event](streamId: StreamId, since: StreamRevision = StreamRevision.Initial, to: StreamRevision = StreamRevision.Maximum)(implicit descriptor: EventStreamType[StreamId, E]): Stream[Commit[E]] = {
      val commitIds = withJedis { _.lrange(keyForStream(descriptor.toString(streamId)), since.value, to.value) }
      doReadCommits(commitIds.asScala).map(commit => commit.copy(events = commit.events.map(descriptor.cast)))
    }
  }

  override object committer extends EventCommitter[Event] {
    private[this] object TryCommitScript extends LuaScript("""
      | local commitsKey = KEYS[1]
      | local streamKey = KEYS[2]
      | local dispatchQueuesKey = KEYS[3]
      | local timestamp = tonumber(ARGV[1])
      | local streamId = ARGV[2]
      | local expected = tonumber(ARGV[3])
      | local events = ARGV[4]
      | local headers = ARGV[5]
      |
      | local actual = tonumber(redis.call('llen', streamKey))
      | if actual ~= expected then
      |   return {'conflict', tostring(actual)}
      | end
      |
      | local storeRevision = tonumber(redis.call('hlen', commitsKey))
      | local commitId = storeRevision + 1
      | local commitData = string.format('{"storeRevision":%d,"timestamp":%d,"streamId":%s,"streamRevision":%d,"events":%s,"headers":%s}',
      |   commitId, timestamp, cjson.encode(streamId), actual + 1, events, headers)
      |
      | redis.call('hset', commitsKey, commitId, commitData)
      | redis.call('rpush', streamKey, commitId)
      | redis.call('publish', commitsKey, commitData)
      |
      | for _, queueKey in ipairs(redis.call('smembers', dispatchQueuesKey)) do
      |   redis.call('lpush', queueKey, commitId)
      | end
      |
      | return {'commit', tostring(commitId)}
      """.stripMargin)

    override def tryCommit[E <: Event](changes: Changes[E]): CommitResult[E] = {
      implicit val descriptor = changes.eventStreamType
      val timestamp = DateTimeUtils.currentTimeMillis
      val serializedEvents = Json.stringify(Json.toJson(changes.events))
      val serializedHeaders = Json.stringify(Json.toJson(changes.headers))
      val streamId = descriptor.toString(changes.streamId)
      val response = TryCommitScript.eval(
        CommitsKey, keyForStream(streamId), DispatchQueuesKey)(
        timestamp.toString, streamId, changes.expected.value.toString, serializedEvents, serializedHeaders)

      try {
        response.asInstanceOf[java.util.List[_]].asScala match {
          case Seq("conflict", actual: String) =>
            val conflicting = reader.readStream(changes.streamId, since = changes.expected)
            Left(Conflict(conflicting.flatMap(_.committedEvents)))
          case Seq("commit", storeRevision: String) =>
            Right(Commit(StoreRevision(storeRevision.toLong), timestamp, streamId, changes.expected.next, changes.events, changes.headers))
        }
      } catch {
        case e: Exception =>
          throw new EventStoreException("Error parsing response from Redis TryCommit script: " + response, e)
      }
    }
  }

  override object publisher extends CommitPublisher[Event] {
    import reader._

    override def subscribe[E <: Event: Manifest](since: StoreRevision)(listener: CommitListener[E]): Subscription = {
      @volatile var cancelled = false
      @volatile var last = since
      val unsubscribeToken = UUID.randomUUID.toString

      def replayCommitsTo(to: StoreRevision) {
        if (last < to) {
          Logger.info("Replaying commits since " + last + " to " + to)
          readCommits(last, to).takeWhile(_ => !closed && !cancelled).foreach(listener)
          last = to
        }
      }

      object Subscriber extends JedisPubSub with RedisPubSubNoOps {
        override def onSubscribe(channel: String, subscribedChannels: Int) = channel match {
          case ControlChannel =>
            // We may have missed the cancellation token while subscribing, so check the flag.
            if (closed || cancelled) unsubscribe
          case CommitsKey =>
            // We may have missed some commits while subscribing, so replay missing if needed.
            replayCommitsTo(storeRevision)
          case _ =>
            Logger.warn("message received on unknown channel '" + channel + "'")
        }

        override def onMessage(channel: String, message: String) = channel match {
          case ControlChannel =>
            if (message == CloseToken || message == unsubscribeToken) {
              unsubscribe
            }
          case CommitsKey =>
            val commit = deserializeCommit(message)
            if (last.next < commit.storeRevision) {
              Logger.warn("missing commits since " + last + " to " + commit.storeRevision + ", replaying...")
              replayCommitsTo(commit.storeRevision)
            } else if (last.next == commit.storeRevision) {
              listener(commit.withOnlyEventsOfType[E])
              last = commit.storeRevision
            } else {
              Logger.warn("Ignoring old commit " + commit.storeRevision + ", since we already processed everything up to " + last)
            }
          case _ =>
            Logger.warn("message received on unknown channel '" + channel + "'")
        }
      }

      val currentRevision = storeRevision
      if (last > currentRevision) {
        Logger.warn("Last " + last + " is in the future, resetting it to current " + currentRevision)
        last = currentRevision
      } else {
        replayCommitsTo(currentRevision)
      }

      Future[Unit] {
        subscribeToChannels(ControlChannel, CommitsKey)(Subscriber)
      }

      new Subscription {
        override def cancel() = {
          cancelled = true
          withJedis { _.publish(ControlChannel, unsubscribeToken) }
          ()
        }

        override def toString = s"Subscription($last, $cancelled, ${RedisEventStore.this})"
      }
    }
  }

  /**
   * Deletes all data from the event store. Use carefully!
   */
  def truncate_!(): Unit = withJedis { jedis: BinaryJedis =>
    val keys = jedis.keys(s"$name:*".getBytes("UTF-8"))
    keys.asScala.grouped(ChunkSize).foreach { group => jedis.del(group.toArray: _*) }
  }

  /**
   * Closes the event store. All subscribers will be unsubscribed and connections to Redis will be closed.
   */
  def close(): Unit = {
    closed = true
    withJedis { _.publish(ControlChannel, CloseToken) }
    executionContext.shutdown()
    if (!executionContext.awaitTermination(5, TimeUnit.SECONDS)) {
      executionContext.shutdownNow
    }
    ()
  }

  override def toString = s"RedisEventStore($name)"
}
