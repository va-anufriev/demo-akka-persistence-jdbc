/*
 * Copyright 2016 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.dnvriend

import akka.actor.{ ActorRef, ActorSystem, Props }
import akka.event.LoggingReceive
import akka.persistence.PersistentActor
import com.typesafe.config.ConfigFactory
import akka.pattern.ask
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.{ ActorMaterializer, Materializer }
import akka.util.Timeout

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

object CloseConnectionsApp extends App {
  implicit val timeout = Timeout(1.second)
  val configName = "default-application.conf"
  lazy val configuration = ConfigFactory.load(configName)
  implicit val system = ActorSystem("app", configuration)
  implicit val mat: Materializer = ActorMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
  val readJournal: JdbcReadJournal = PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
  sys.addShutdownHook(system.terminate())

  val actorResult: Future[Any] = system.actorOf(Props(new PersistentActor {
    override def persistenceId: String = "the-guy"

    override def receiveRecover: Receive = {
      case x ⇒ println("recover  : " + x + ", seqNr: " + lastSequenceNr)
    }

    def handle(to: ActorRef, it: String): Unit = {
      println("persisted: " + it + ", seqNr: " + lastSequenceNr)
      to ! "hello"
    }

    override def receiveCommand: Receive = LoggingReceive {
      case c: String        ⇒ persist(c)(handle(sender(), _))
      case xs: List[String] ⇒ persistAll(xs)(handle(sender(), _))
    }
  })) ? List.fill(25)("hello world")

  Await.ready(for {
    _ ← actorResult
    _ ← readJournal.allPersistenceIds().take(1).runForeach(pid ⇒ println(s": >>== Received PersistenceId: $pid ==<< :"))
    _ ← readJournal.eventsByPersistenceId("the-guy", 0, Long.MaxValue).take(25).runForeach(envelope ⇒ println(s": >>== Received envelope: $envelope ==<< :"))
  } yield (), 5.seconds)
  Await.ready(system.terminate(), 5.seconds)
}
