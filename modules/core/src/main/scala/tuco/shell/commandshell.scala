package tuco.shell

import tuco.free._
import tuco.free.{ connection => FC }
import tuco.free.{ basicterminalio => FBT }
import tuco.free.basicterminalio.BasicTerminalIOIO
import tuco.hi.{ connection => HC }
import net.wimpi.telnetd.net.Connection
import net.bmjames.opts._

import scalaz._, Scalaz.{ some => _, _ }, scalaz.effect._

object CommandShell {

  private val Bang = "!(.*)".r

  private val eventNotFound =
    HC.writeLn(s"history: event not found")

  /**
   * Run a command REPL using the given initial `Session`, yielding the final session value. This
   * action repeats until an executed action yields a session where `.done` is true.
   */
  def run[A](s: Session[A]): FC.ConnectionIO[Session[A]] =
    if (s.done) {
      s.point[FC.ConnectionIO]
    } else {

      def cmd(c: String) = s.commands.interp(c)(s).flatMap(s => run(c :: s))

      def com(conn: Connection)(p: String): FBT.BasicTerminalIOIO[String \/ List[String]] = {

        // In the special case of a prefix in the form <cmd><spaces>...<spaces><prefix> we
        // will delegate to the command's argument completer, if any.
        s.commands.toList.find(c => p.startsWith(c.name + " ")) match {
          case None    =>
            (s.commands.toList.map(_.name).filter(_.startsWith(p)) match {
              case s :: Nil => -\/(s.drop(p.length))
              case ss       => \/-(ss)
            }).point[FBT.BasicTerminalIOIO]
          case Some(c) =>
            val (p0, p1) = p.splitAt(p.lastIndexOf(" ") + 1)
            val cs = FBT.lift(conn, c.complete(s, p1))
            cs map {
              case s :: Nil => -\/(s.drop(p1.length))
              case ss       => \/-(ss)
            }
        }

      }

      FC.raw(identity).flatMap { conn =>
        HC.readLn(s.prompt, history = s.history.toZipper, complete = com(conn)).flatMap {
          case Bang(c) => s.history.recall(c).fold(eventNotFound.as(s))(cmd)
          case c       => cmd(c)
        }
      }
    }

}
