package net.zortal.telegram.bot

import cats.effect._
import zio._
import zio.console._
import zio.interop.catz._
import zio.system
import zio.system._
import zio.clock.{ Clock => ZClock }
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s._
import scala.concurrent.ExecutionContext.global
import java.time.ZonedDateTime
import zio.internal.{ Platform, PlatformLive }
import net.zortal.telegram.bot.{ TelegramBot, ZortalFeedApi }
import zio.duration.Duration

case class State(lastCheck: ZonedDateTime)
case class Article(published: ZonedDateTime, title: String, link: String)

case class Config(
  zortalFeedEndpoint: String,
  telegramEndpoint: String,
  feedCheckDelaySeconds: Int,
)

object Main extends App {

  override val platform: Platform = PlatformLive.Default.withReportFailure(_ => ())

  def reqFeed(state: Ref[State]) =
    for {
      lastCheck              <- state.get.map(_.lastCheck)
      (updated, newArticles) <- ZortalFeedApi.>.getFeed(lastCheck)
      _ <- if (newArticles.length > 0)
            TelegramBot.>.sendArticles(newArticles.take(2))
          else ZIO.unit
      _ <- state.update(_.copy(lastCheck = updated))
    } yield ()

  def handleZortalFeed(state: Ref[State], feedCheckDelay: Duration) = {

    val onError = for {
      now <- clock.currentDateTime
      _   <- state.update(_.copy(lastCheck = now.toZonedDateTime))
      _   <- putStrLn("Some error with Zortal feed")
    } yield ()

    reqFeed(state)
      .catchAll(_ => onError)
      .repeat(Schedule.fixed(feedCheckDelay))
      .unit
  }

  def handleTelegramMessages =
    for {
      envConsole <- ZIO.environment[Console]
      telegramMessages <- TelegramBot.>.handleMessages(_ =>
                           putStrLn("Some error with polling")
                             .provide(envConsole),
                         )
      _ <- telegramMessages
            .foreach(result =>
              ZIO.foreach(result.subscriptions)(ChatRepository.>.saveChat) *>
                ZIO.foreach(result.unsubscriptions)(ChatRepository.>.removeChat),
            )
    } yield ()

  def program(feedCheckDelay: Duration) =
    for {
      now   <- clock.currentDateTime
      state <- Ref.make(State(now.toZonedDateTime))

      _ <- handleTelegramMessages.fork
      _ <- handleZortalFeed(state, feedCheckDelay).fork
    } yield ()

  val httpClientRes = for {
    implicit0(ce: ConcurrentEffect[Task]) <- ZManaged.fromEffect(
                                              ZIO.concurrentEffect[Any],
                                            )
    client <- BlazeClientBuilder[Task](global).resource.toManaged
  } yield client

  // TODO: to config
  val conf = Config(
    "https://zortal.net/index.php/feed/atom/",
    "https://api.telegram.org",
    30,
  )

  override def run(args: List[String]): ZIO[ZEnv, Nothing, Int] =
    httpClientRes
      .use(httpClient =>
        for {
          _ <- putStrLn("START")

          zortalUri   <- ZIO.fromEither(Uri.fromString(conf.zortalFeedEndpoint))
          telegramUri <- ZIO.fromEither(Uri.fromString(conf.telegramEndpoint))
          telegramBotToken <- system
                               .env("ZORTAL_TOKEN")
                               .flatMap {
                                 case None =>
                                   ZIO.fail(new Throwable("ZORTAL_TOKEN env variable missing"))
                                 case Some(t) => ZIO.effect(t)
                               }

          inMemChatRepository <- ChatRepository.Dummy.make().map(_.chatRepository)

          liveZortalFeedApi   = ZortalFeedApi(httpClient, zortalUri).zortalFeedApi
          liveTelegramService = TelegramService(httpClient, telegramUri, telegramBotToken)
          liveTelegramBot <- TelegramBot(
                              liveTelegramService.telegramService,
                              inMemChatRepository,
                            ).map(_.telegramBot)

          _ <- program(conf.feedCheckDelaySeconds.seconds).provide(
                new ZortalFeedApi
                  with TelegramBot
                  with ChatRepository
                  with ZClock.Live
                  with Console.Live
                  with System.Live {
                  val zortalFeedApi  = liveZortalFeedApi
                  val telegramBot    = liveTelegramBot
                  val chatRepository = inMemChatRepository
                },
              )

          _ <- ZIO.never
        } yield (),
      )
      .orDie as 1

}