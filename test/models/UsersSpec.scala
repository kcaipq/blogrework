package models

import events._, UserEventsSpec._
import eventstore._
import eventstore.fake.FakeEventStore

@org.junit.runner.RunWith(classOf[org.specs2.runner.JUnitRunner])
class UsersSpec extends support.Spec {
  "Users" should {
    val A = UserId.generate()
    val B = UserId.generate()
    val emailAddress = EmailAddress("john@example.com")
    val password = Password.fromPlainText("password")
    val password2 = Password.fromPlainText("password2")
    val displayName = "John Doe"
    val authenticationToken = AuthenticationToken.generate()
    val authenticationToken2 = AuthenticationToken.generate()

    "contain registered users" in {
      val user = given(UserRegistered(A, emailAddress, displayName, password)).user(A)
      user must_== RegisteredUser(A, StreamRevision(1), emailAddress, displayName, password)
    }

    "store current profile for user" in {
      val user = given(UserRegistered(A, emailAddress, displayName, password), UserProfileChanged(A, "Updated")).user(A)
      user must_== RegisteredUser(A, StreamRevision(2), emailAddress, "Updated", password)
    }

    "store current password for user" in {
      val user = given(UserRegistered(A, emailAddress, displayName, password), UserPasswordChanged(A, password2)).user(A)
      user must_== RegisteredUser(A, StreamRevision(2), emailAddress, displayName, password2)
    }

    "track users by initial email address" in {
      val users = given(UserRegistered(A, emailAddress, displayName, password)).users
      users.findByEmail(emailAddress) must beSome(RegisteredUser(A, StreamRevision(1), emailAddress, displayName, password))
    }

    "track users by changed email address" in {
      val updated = EmailAddress("updated@example.com")
      val users = given(UserRegistered(A, emailAddress, displayName, password), UserEmailAddressChanged(A, updated)).users
      users.findByEmail(emailAddress) must beNone
      users.findByEmail(updated) must beSome(RegisteredUser(A, StreamRevision(2), updated, displayName, password))
    }

    "track current authentication token when logged in" in {
      val users = given(UserRegistered(A, emailAddress, displayName, password), UserLoggedIn(A, authenticationToken)).users
      users.findByAuthenticationToken(authenticationToken) must beSome(RegisteredUser(A, StreamRevision(2), emailAddress, displayName, password, Some(authenticationToken)))
    }

    "remove current authentication token logged out" in {
      val users = given(UserRegistered(A, emailAddress, displayName, password), UserLoggedIn(A, authenticationToken), UserLoggedOut(A)).users

      users.findByAuthenticationToken(authenticationToken) must beNone
      users.get(A) must beSome(RegisteredUser(A, StreamRevision(3), emailAddress, displayName, password, None))
    }

    "remove previous authentication token when when logged in again" in {
      val users = given(
        UserRegistered(A, emailAddress, displayName, password),
        UserLoggedIn(A, authenticationToken),
        UserLoggedIn(A, authenticationToken2)).users

      users.findByAuthenticationToken(authenticationToken) must beNone
      users.findByAuthenticationToken(authenticationToken2) must beSome(RegisteredUser(A, StreamRevision(3), emailAddress, displayName, password, Some(authenticationToken2)))
    }

    "track the stream revision per user" in eventsForMultipleUsers { events =>
      val fixture = given(events: _*)
      fixture.streamRevisions must haveAllElementsLike {
        case (userId, revision) => fixture.users.get(userId).map(_.revision must_== revision).getOrElse(ok)
      }
    }
  }

  case class given(events: UserEvent*) extends eventstore.MemoryImageFixture(events: _*) {
    val users = eventsWithRevision.foldLeft(Users()) {
      case (users, (event, revision)) => users.update(event, revision)
    }

    def user(id: UserId) = users.get(id).getOrElse(failure("user not found: " + id))
  }
}
