package mesosphere.marathon.health

import akka.actor.{ Actor, ActorLogging, ActorRef, Cancellable, Props }
import akka.event.EventStream
import mesosphere.marathon.MarathonSchedulerDriver
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event._
import mesosphere.marathon.state.PathId
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.mesos.protos.TaskID

class HealthCheckActor(
    appId: PathId,
    healthCheck: HealthCheck,
    taskTracker: TaskTracker,
    eventBus: EventStream) extends Actor with ActorLogging {

  import context.dispatcher
  import mesosphere.marathon.health.HealthCheckActor.GetTaskHealth
  import mesosphere.marathon.health.HealthCheckWorker.HealthCheckJob
  import mesosphere.mesos.protos.Implicits._

  protected[this] var nextScheduledCheck: Option[Cancellable] = None

  protected[this] var taskHealth = Map[String, Health]()

  override def preStart(): Unit = {
    log.info(
      "Starting health check actor for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )
    scheduleNextHealthCheck()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit =
    log.info(
      "Restarting health check actor for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )

  override def postStop(): Unit = {
    nextScheduledCheck.forall { _.cancel() }
    log.info(
      "Stopped health check actor for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )
  }

  // self-sent every healthCheck.intervalSeconds
  protected[this] case object Tick

  protected[this] def purgeStatusOfDoneTasks(): Unit = {
    log.debug(
      "Purging health status of done tasks for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )
    val activeTaskIds = taskTracker.get(appId).map(_.getId)
    taskHealth = taskHealth.filterKeys(activeTaskIds)
  }

  protected[this] def scheduleNextHealthCheck(): Unit = {
    log.debug(
      "Scheduling next health check for app [{}] and healthCheck [{}]",
      appId,
      healthCheck
    )
    nextScheduledCheck = Some(
      context.system.scheduler.scheduleOnce(healthCheck.interval) {
        self ! Tick
      }
    )
  }

  protected[this] def dispatchJobs(): Unit = {
    log.debug("Dispatching health check jobs to workers")
    taskTracker.get(appId).foreach { task =>
      log.debug("Dispatching health check job for task [{}]", task.getId)
      val worker: ActorRef = context.actorOf(Props[HealthCheckWorkerActor])
      worker ! HealthCheckJob(task, healthCheck)
    }
  }

  protected[this] def checkConsecutiveFailures(task: MarathonTask,
                                               health: Health): Unit = {
    val consecutiveFailures = health.consecutiveFailures
    val maxFailures = healthCheck.maxConsecutiveFailures

    // ignore failures if maxFailures == 0
    if (consecutiveFailures >= maxFailures && maxFailures > 0) {
      log.info(f"Killing task ${task.getId} on host ${task.getHost}")

      // kill the task
      MarathonSchedulerDriver.driver.foreach { driver =>
        driver.killTask(TaskID(task.getId))
      }

      // increase the task launch delay for this questionably healthy app
      MarathonSchedulerDriver.scheduler.foreach { scheduler =>
        scheduler.unhealthyTaskKilled(appId, task.getId)
      }
    }
  }

  protected[this] def ignoreFailures(task: MarathonTask,
                                     health: Health): Boolean = {
    // Ignore failures during the grace period, until the task becomes green
    // for the first time.  Also ignore failures while the task is staging.
    !task.hasStartedAt ||
      health.firstSuccess.isEmpty &&
      task.getStartedAt + healthCheck.gracePeriod.toMillis > System.currentTimeMillis()
  }

  def receive = {
    case GetTaskHealth(taskId) => sender ! taskHealth.get(taskId)
    case Tick =>
      purgeStatusOfDoneTasks()
      dispatchJobs()
      scheduleNextHealthCheck()

    case result: HealthResult =>
      log.info("Received health result: [{}]", result)
      val taskId = result.taskId
      val health = taskHealth.getOrElse(taskId, Health(taskId))

      val newHealth = result match {
        case Healthy(_, _, _) =>
          health.update(result)
        case Unhealthy(_, _, _, _) =>
          taskTracker.get(appId).find(_.getId == taskId) match {
            case Some(task) =>
              if (ignoreFailures(task, health)) {
                // Don't update health
                health
              }
              else {
                eventBus.publish(FailedHealthCheck(appId, taskId, healthCheck))
                checkConsecutiveFailures(task, health)
                health.update(result)
              }
            case None =>
              log.error(s"Couldn't find task $taskId")
              health.update(result)
          }
      }

      taskHealth += (taskId -> newHealth)

      if (health.alive != newHealth.alive) {
        eventBus.publish(
          HealthStatusChanged(
            appId = appId,
            taskId = taskId,
            version = result.version,
            alive = newHealth.alive)
        )
      }
  }
}

object HealthCheckActor {
  case class GetTaskHealth(taskId: String)
}
