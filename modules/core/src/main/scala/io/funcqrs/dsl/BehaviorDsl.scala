package io.funcqrs.dsl

import io.funcqrs.{AggregateAliases, AggregateLike, Behavior, CommandException}

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.Try

class BehaviorDsl[A <: AggregateLike] extends AggregateAliases {

  type Aggregate = A
  type CreationCommandToEventMagnet = CreationBuilder#CommandToEventMagnet
  type CreationEventToAggregate = CreationBuilder#EventToAggregate

  case class CreationBuilder(processCommandFunction: CreationCommandToEventMagnet = PartialFunction.empty,
                             handleEventFunction: CreationEventToAggregate = PartialFunction.empty) {

    sealed trait EventMagnet {

      def apply(): Future[Event]
    }

    object EventMagnet {

      implicit def fromSingleEvent(event: Event): EventMagnet =
        new EventMagnet {
          def apply() = Future.successful(event)
        }

      implicit def fromTrySingleEvent(event: Try[Event]): EventMagnet =
        new EventMagnet {
          def apply() = Future.fromTry(event)
        }

      implicit def fromAsyncSingleEvent(event: Future[Event]): EventMagnet =
        new EventMagnet {
          def apply() = event
        }

      implicit def fromException(ex: Exception): EventMagnet =
        new EventMagnet {
          def apply() = Future.failed(ex)
        }
    }

    // from Cmd to EventMagnet
    type CommandToEventMagnet = PartialFunction[Command, EventMagnet]

    // from Event to new Aggregate
    type EventToAggregate = PartialFunction[Event, Aggregate]

  }

  type UpdatesCommandToEventMagnet = UpdatesBuilder#CommandToEventMagnet
  type UpdatesEventToAggregate = UpdatesBuilder#EventToAggregate

  case class UpdatesBuilder(processCommandFunction: UpdatesCommandToEventMagnet = PartialFunction.empty,
                            handleEventFunction: UpdatesEventToAggregate = PartialFunction.empty) {

    private implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.global

    sealed trait EventMagnet {

      def apply(): Future[Events]
    }

    object EventMagnet {

      implicit def fromSingleEvent(event: Event): EventMagnet =
        new EventMagnet {
          def apply() = Future.successful(immutable.Seq(event))
        }

      implicit def fromImmutableEvents(events: Events): EventMagnet =
        new EventMagnet {
          def apply() = Future.successful(events)
        }

      implicit def fromTrySingleEvent(event: Try[Event]): EventMagnet =
        new EventMagnet {
          def apply() = Future.fromTry(event.map(immutable.Seq(_)))
        }

      implicit def fromAsyncSingleEvent(event: Future[Event]): EventMagnet =
        new EventMagnet {
          def apply() = event.map(immutable.Seq(_))
        }

      implicit def fromTryImmutableEvents(events: Try[Events]): EventMagnet =
        new EventMagnet {
          def apply() = Future.fromTry(events)
        }

      implicit def fromAsyncImmutableEvents(events: Future[Events]): EventMagnet =
        new EventMagnet {
          def apply() = events
        }

      implicit def fromException(ex: Exception): EventMagnet =
        new EventMagnet {
          def apply() = Future.failed(ex)
        }
    }

    // from Aggregate + Cmd to EventMagnet
    type CommandToEventMagnet = PartialFunction[(Aggregate, Command), EventMagnet]

    type EventToAggregate = PartialFunction[(Aggregate, Event), Aggregate]

  }

  trait BuildState

  trait Pending extends BuildState

  trait CreationDefined extends BuildState

  trait UpdatesDefined extends BuildState

  object BehaviorBuilder {
    implicit def build(builder: BehaviorBuilder[CreationDefined, UpdatesDefined]): Behavior[Aggregate] = {
      import builder._

      new Behavior[Aggregate] {

        private val processCreationalCommands = creationBuilder.processCommandFunction
        private val handleCreationalEvents = creationBuilder.handleEventFunction
        private val fallbackOnCreation: creationBuilder.CommandToEventMagnet = {
          case cmd => new CommandException(s"Invalid command $cmd")
        }

        private val processUpdateCommands = updatesBuilder.processCommandFunction
        private val handleEvents = updatesBuilder.handleEventFunction
        private val fallbackOnUpdate: updatesBuilder.CommandToEventMagnet = {
          case (agg, cmd) => new CommandException(s"Invalid command $cmd for aggregate ${agg.id}")
        }

        def validateAsync(cmd: Command)(implicit ec: ExecutionContext): Future[Event] = {
          (processCreationalCommands orElse fallbackOnCreation)
            .apply(cmd) // apply cmd
            .apply() // apply magnet
        }

        def validateAsync(cmd: Command, aggregate: Aggregate)(implicit ec: ExecutionContext): Future[Events] = {
          (processUpdateCommands orElse fallbackOnUpdate)
            .apply(aggregate, cmd) // apply cmd
            .apply() // apply magnet
        }

        def applyEvent(evt: Event): Aggregate = {
          handleCreationalEvents(evt)
        }

        def applyEvent(evt: Event, aggregate: Aggregate): Aggregate = {
          val handleNoEvents: UpdatesEventToAggregate = {
            case (aggregate, _) => aggregate
          }
          (handleEvents orElse handleNoEvents) (aggregate, evt)
        }

        def isEventDefined(event: Event): Boolean =
          handleCreationalEvents.isDefinedAt(event)

        def isEventDefined(event: Event, aggregate: Aggregate): Boolean =
          handleEvents.isDefinedAt(aggregate, event)
      }
    }
  }

  case class BehaviorBuilder[C <: BuildState, U <: BuildState](creationBuilder: CreationBuilder, updatesBuilder: UpdatesBuilder) {
    def whenConstructing(newCreationBuilder: => CreationBuilder): BehaviorBuilder[CreationDefined, U] =
      copy(creationBuilder = newCreationBuilder)

    def whenUpdating(newUpdatesBuilder: => UpdatesBuilder): BehaviorBuilder[C, UpdatesDefined] =
      copy(updatesBuilder = newUpdatesBuilder)
  }

  object theCreationBuilder extends CreationBuilder

  object theUpdatesBuilder extends UpdatesBuilder

  object theBehaviorBuilder extends BehaviorBuilder[Pending, Pending](creationBuilder = theCreationBuilder, updatesBuilder = theUpdatesBuilder) {

    def processCreationCommands(c2em: theCreationBuilder.CommandToEventMagnet)(e2a: theCreationBuilder.EventToAggregate) = {
      theCreationBuilder
        .copy(
          processCommandFunction = theCreationBuilder.processCommandFunction orElse c2em,
          handleEventFunction = theCreationBuilder.handleEventFunction orElse e2a)
    }

    def processUpdatesCommands(c2em: theUpdatesBuilder.CommandToEventMagnet)(e2a: theUpdatesBuilder.EventToAggregate): UpdatesBuilder = {
      theUpdatesBuilder
        .copy(
          processCommandFunction = theUpdatesBuilder.processCommandFunction orElse c2em,
          handleEventFunction = theUpdatesBuilder.handleEventFunction orElse e2a)
    }

  }

}

object BehaviorDsl {
  def behaviorFor[A <: AggregateLike] = {
    new BehaviorDsl[A].theBehaviorBuilder
  }
}