package support

import events._
import play.api.data._
import play.api.data.Forms._

object Forms {
  val trimmedText = text.transform[String](_.trim, identity)
  val tokenizedText = trimmedText.transform[String](_.replaceAll("\\s+", " "), identity)
  val emailAddress = trimmedText.verifying(email.constraints: _*).transform[EmailAddress](EmailAddress.apply, _.value)
  val password = text.transform[String](identity, _ => "")
}
