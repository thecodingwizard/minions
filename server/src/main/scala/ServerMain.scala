package minionsgame.server

import scala.util.{Try,Success,Failure}
import scala.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import com.typesafe.config.ConfigFactory
import java.util.Calendar
import java.text.SimpleDateFormat

import akka.actor.{ActorSystem, Actor, ActorRef, Terminated, Props, Status}
import akka.stream.{ActorMaterializer,OverflowStrategy}
import akka.stream.scaladsl.{Flow,Sink,Source}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message,TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.event.Logging

import minionsgame.core._
import RichImplicits._

object Paths {
  val applicationConf = "./application.conf"
  val mainPage = "./web/index.html"
  val webjs = "./web/js/"
}

object ServerMain extends App {
  //----------------------------------------------------------------------------------
  //LOAD STUFF

  val config = ConfigFactory.parseFile(new java.io.File(Paths.applicationConf))

  implicit val actorSystem = ActorSystem("gameSystem",config)
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher

  val timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ")
  def log(s: String): Unit = {
    println(timeFormat.format(Calendar.getInstance().getTime()) + " " + s)
  }

  val cwd = new java.io.File(".").getCanonicalPath
  log("Running in " + cwd)

  val interface = config.getString("app.interface")
  val port = config.getInt("app.port")

  //----------------------------------------------------------------------------------
  //GAME AND BOARD SETUP

  val numBoards = config.getInt("app.numBoards")
  val game = {
    val startingMana = SideArray.create(0)
    startingMana(S0) = config.getInt("app.s0StartingManaPerBoard") * numBoards
    startingMana(S1) = config.getInt("app.s1StartingManaPerBoard") * numBoards
    val techsAlwaysAcquired: Array[Tech] = Array(
      PieceTech(Units.zombie.name),
      PieceTech(Units.acolyte.name),
      PieceTech(Units.spire.name)
    )
    val lockedTechs: Array[Tech] = {
      List(
        Units.initiate,
        Units.skeleton,
        Units.serpent,
        Units.bat,
        Units.ghost,
        Units.wight,
        Units.haunt,
        Units.shrieker,
        Units.warg
      ).map { unit =>
        PieceTech(unit.name)
      }.toArray
    }
    val extraTechCost = config.getInt("app.extraTechCostPerBoard") * numBoards

    val game = Game(
      numBoards = numBoards,
      startingSide = S0,
      startingMana = startingMana,
      extraTechCost = extraTechCost,
      techsAlwaysAcquired = techsAlwaysAcquired,
      lockedTechs = lockedTechs
    )
    game
  }
  var gameSequence = 0

  val boards: Array[Board] = {
    val boards = (0 until numBoards).toArray.map { _ =>
      val state = BoardState.create(BoardMaps.basic)
      val necroNames = SideArray.create(Units.necromancer.name)
      state.resetBoard(necroNames)

      //Testing
      {
        state.spawnPieceInitial(S0, Units.test.name, Loc(5,5))
        state.spawnPieceInitial(S0, Units.test.name, Loc(5,5))
        state.spawnPieceInitial(S0, Units.test.name, Loc(5,5))
        state.spawnPieceInitial(S0, Units.haunt.name, Loc(5,6))
        state.spawnPieceInitial(S0, Units.shrieker.name, Loc(5,4))

        state.spawnPieceInitial(S1, Units.test.name, Loc(6,5))
        state.spawnPieceInitial(S1, Units.test.name, Loc(6,5))
        state.spawnPieceInitial(S1, Units.test.name, Loc(6,5))

        state.spawnPieceInitial(S1, Units.wight.name, Loc(6,6))

        state.addReinforcementInitial(S0,"zombie")
        state.addReinforcementInitial(S0,"bat")
        state.addReinforcementInitial(S0,"bat")
        state.addReinforcementInitial(S0,"bat")

        state.addReinforcementInitial(S1,"zombie")
        state.addReinforcementInitial(S1,"zombie")
        state.addReinforcementInitial(S1,"bat")
        state.addReinforcementInitial(S1,"bat")
      }

      Board.create(state)
    }
    boards
  }
  val boardSequences: Array[Int] = (0 until numBoards).toArray.map { _ => 0}

  //----------------------------------------------------------------------------------
  //GAME ACTOR - singleton actor that maintains the state of the game being played

  sealed trait GameActorEvent
  case class UserJoined(val sessionId: Int, val username: String, val side: Option[Side], val out: ActorRef) extends GameActorEvent
  case class UserLeft(val sessionId: Int) extends GameActorEvent
  case class QueryStr(val sessionId: Int, val queryStr: String) extends GameActorEvent

  private class GameActor extends Actor {
    //The actor refs are basically the writer end of a pipe where we can stick messages to go out
    //to the players logged into the server
    var sessionOfUsername: Map[String,Int] = Map()
    var usernameOfSession: Map[Int,String] = Map()
    var userSides: Map[Int,Option[Side]] = Map()
    var userOuts: Map[Int,ActorRef] = Map()

    // private def broadcast(response: Protocol.Response, side: Option[Side]): Unit = {
    //   userOuts.foreach { case (sid,out) =>
    //     if(userSides(sid) == side) out ! response
    //   }
    // }
    private def broadcastAll(response: Protocol.Response): Unit = {
      userOuts.foreach { case (_,out) =>
        out ! response
      }
    }

    private def performAndBroadcastGameActionIfLegal(gameAction: GameAction): Try[Unit] = {
      game.doAction(gameAction).map { case () =>
        //If successful, report the event
        gameSequence += 1
        broadcastAll(Protocol.ReportGameAction(gameAction,gameSequence))
      }
    }

    //Called upon performing a sucessful board action - unsets any user flag that the
    //board is done.
    private def maybeUnsetBoardDone(boardIdx: Int): Unit = {
      if(game.isBoardDone(boardIdx)) {
        val gameAction: GameAction = SetBoardDone(boardIdx,false)
        val (_: Try[Unit]) = performAndBroadcastGameActionIfLegal(gameAction)
      }
    }

    private def doResetBoard(boardIdx: Int): Unit = {
      //TODO necromancer randomization
      val necroNames = SideArray.create(Units.necromancer.name)
      boards(boardIdx).resetBoard(necroNames)
      broadcastAll(Protocol.ReportResetBoard(boardIdx,necroNames))
    }

    private def maybeDoEndOfTurn(): Unit = {
      if(game.isBoardDone.forall { isDone => isDone })
        doEndOfTurn()
    }

    private def doEndOfTurn(): Unit = {
      val oldSide = game.curSide
      val newSide = game.curSide.opp

      //Check win condition and reset boards as needed
      for(boardIdx <- 0 until boards.length) {
        val board = boards(boardIdx)
        if(board.curState.hasWon) {
          val gameAction: GameAction = AddWin(oldSide)
          val (_: Try[Unit]) = performAndBroadcastGameActionIfLegal(gameAction)
          doResetBoard(boardIdx)
        }
      }

      //Accumulate mana on all the boards for the side about to move
      val mana = boards.foldLeft(0) { case (sum,board) =>
        sum + board.curState.manaThisRound(newSide)
      }
      game.addMana(newSide,mana)

      //Automatically tech if it hasn't happened yet, as a convenience
      if(!game.hasTechedThisTurn) {
        val idx = game.techLine.indexWhere { techState => techState.level(oldSide) == TechLocked}
        if(idx >= 0) { //-1 if not found
          val (_: Try[Unit]) = performAndBroadcastGameActionIfLegal(PerformTech(oldSide,idx))
        }
      }

      game.endTurn()
      boards.foreach { board => board.endTurn() }
      broadcastAll(Protocol.ReportNewTurn(newSide))
    }

    private def handleQuery(query: Protocol.Query, out: ActorRef, side: Option[Side]): Unit = {
      query match {
        case Protocol.Heartbeat(i) =>
          out ! Protocol.OkHeartbeat(i)

        case Protocol.RequestBoardHistory(boardIdx) =>
          if(boardIdx < 0 || boardIdx >= numBoards)
            out ! Protocol.QueryError("Invalid boardIdx")
          else {
            out ! Protocol.ReportBoardHistory(
              boardIdx,
              boards(boardIdx).toSummary(),
              boardSequences(boardIdx)
            )
          }

        case Protocol.DoBoardAction(boardIdx,boardAction) =>
          log("Received board " + boardIdx + " action " + boardAction)
          side match {
            case None =>
              out ! Protocol.QueryError("Cannot perform actions as a spectator")
            case Some(side) =>
              if(boardIdx < 0 || boardIdx >= numBoards)
                out ! Protocol.QueryError("Invalid boardIdx")
              else if(boards(boardIdx).curState().side != side)
                out ! Protocol.QueryError("Currently the other team's turn")
              else {
                //Some board actions are special and are meant to be server -> client only, or need extra checks
                val specialResult: Try[Unit] = boardAction match {
                  case (_: PlayerActions) => Success(())
                  case (_: LocalPieceUndo) => Success(())
                  case (_: SpellUndo) => Success(())
                  case BuyReinforcementUndo(pieceName,_) =>
                    //Check ahead of time if it's legal
                    boards(boardIdx).tryLegality(boardAction).flatMap { case () =>
                      //And if so, go ahead and recover the cost of the unit
                      val gameAction: GameAction = UnpayForReinforcement(side,pieceName)
                      performAndBroadcastGameActionIfLegal(gameAction)
                    }
                  case DoGeneralBoardAction(generalBoardAction,_) =>
                    generalBoardAction match {
                      case BuyReinforcement(pieceName) =>
                        //Pay for the cost of the unit
                        val gameAction: GameAction = PayForReinforcement(side,pieceName)
                        performAndBroadcastGameActionIfLegal(gameAction)
                      case GainSpell(_) =>
                        Failure(new Exception("Not implemented yet"))
                      case RevealSpell(_,_,_) =>
                        Failure(new Exception("Only server allowed to send this action"))
                    }
                }

                specialResult.flatMap { case () => boards(boardIdx).doAction(boardAction) } match {
                  case Failure(e) =>
                    out ! Protocol.QueryError("Illegal action: " + e.toString)
                  case Success(()) =>
                    //If this board was set as done, then since we did an action on it, unset it.
                    maybeUnsetBoardDone(boardIdx)

                    boardSequences(boardIdx) += 1
                    out ! Protocol.OkBoardAction(boardIdx,boardSequences(boardIdx))
                    broadcastAll(Protocol.ReportBoardAction(boardIdx,boardAction,boardSequences(boardIdx)))
                }
              }
          }

        case Protocol.DoGameAction(gameAction) =>
          log("Received game action " + gameAction)
          side match {
            case None =>
              out ! Protocol.QueryError("Cannot perform actions as a spectator")
            case Some(side) =>
              if(game.curSide != side)
                out ! Protocol.QueryError("Currently the other team's turn")
              else {
                //Some game actions are special and are meant to be server -> client only, or need extra checks
                val specialResult: Try[Unit] = gameAction match {
                  case (_: PerformTech) | (_: SetBoardDone) => Success(())
                  case (_: PayForReinforcement) | (_: UnpayForReinforcement) | (_: AddWin) =>
                    Failure(new Exception("Only server allowed to send this action"))
                }
                specialResult.flatMap { case () => game.doAction(gameAction) } match {
                  case Failure(e) =>
                    out ! Protocol.QueryError("Illegal action: " + e.toString)
                  case Success(()) =>
                    gameSequence += 1
                    out ! Protocol.OkGameAction(gameSequence)
                    broadcastAll(Protocol.ReportGameAction(gameAction,gameSequence))
                    maybeDoEndOfTurn()
                }
              }
          }

      }
    }

    def terminateWebsocket(out: ActorRef): Unit = {
      //Websocket closes if you send it Status.Success
      out ! Status.Success("")
    }

    def handleUserLeft(sessionId: Int) = {
      if(usernameOfSession.contains(sessionId)) {
        val username = usernameOfSession(sessionId)
        val side = userSides(sessionId)
        broadcastAll(Protocol.UserLeft(username,side))
        val out = userOuts(sessionId)
        usernameOfSession = usernameOfSession - sessionId
        userSides = userSides - sessionId
        userOuts = userOuts - sessionId
        terminateWebsocket(out)
        log("UserLeft: " + username + " Side: " + side)
      }
    }

    override def receive: Receive = {
      case UserJoined(sessionId, username, side, out) =>
        if(sessionOfUsername.contains(username)) {
          val oldSessionId = sessionOfUsername(username)
          handleUserLeft(oldSessionId)
        }

        sessionOfUsername = sessionOfUsername + (username -> sessionId)
        usernameOfSession = usernameOfSession + (sessionId -> username)
        userSides = userSides + (sessionId -> side)
        userOuts = userOuts + (sessionId -> out)
        out ! Protocol.Version(CurrentVersion.version)
        out ! Protocol.Initialize(game, boards.map { board => board.toSummary()},boardSequences.clone())
        broadcastAll(Protocol.UserJoined(username,side))
        log("UserJoined: " + username + " Side: " + side)

      case UserLeft(sessionId) =>
        if(usernameOfSession.contains(sessionId)) {
          val username = usernameOfSession(sessionId)
          val side = userSides(sessionId)
          broadcastAll(Protocol.UserLeft(username,side))
          val out = userOuts(sessionId)
          usernameOfSession = usernameOfSession - sessionId
          userSides = userSides - sessionId
          userOuts = userOuts - sessionId
          out ! Status.Success("")
          log("UserLeft: " + username + " Side: " + side)
        }
      case QueryStr(sessionId, queryStr) =>
        if(usernameOfSession.contains(sessionId)) {
          val out = userOuts(sessionId)
          import play.api.libs.json._
          Try(Json.parse(queryStr)) match {
            case Failure(err) => out ! Protocol.QueryError("Could not parse as json: " + err.toString)
            case Success(json) =>
              json.validate[Protocol.Query] match {
                case (e: JsError) => out ! Protocol.QueryError("Could not parse as query: " + JsError.toJson(e).toString())
                case (s: JsSuccess[Protocol.Query]) =>
                  val query = s.get
                  handleQuery(query, out, userSides(sessionId))
              }
          }
        }
    }

  }

  //----------------------------------------------------------------------------------
  //WEBSOCKET MESSAGE HANDLING

  val gameActor = actorSystem.actorOf(Props(classOf[GameActor]))
  val nextSessionId = new AtomicInteger()

  def websocketMessageFlow(username: String, sideStr: Option[String]) : Flow[Message, Message, _] = {
    val side: Option[Side] = sideStr match {
      case Some("0") => Some(S0)
      case Some("1") => Some(S1)
      case Some(s) => throw new Exception("Invalid side: " + s)
      case None => None
    }

    val sessionId = nextSessionId.getAndIncrement()

    //Create output stream for the given user
    val responseBufferSize = 16 //Buffer messages to the user before failing

    //Specifies a sink where the values are made by a flow of Messages
    //and mapping them and then feeding them to the GameActor
    val in: Sink[Message,_] = {
      Flow[Message].collect { message: Message =>
        message match {
          case TextMessage.Strict(text) =>
            Future.successful(text)
          case TextMessage.Streamed(textStream) =>
            textStream.runFold("")(_ + _)
        }
      } .mapAsync(1)((str:Future[String]) => str)
        .map { (str: String) => QueryStr(sessionId,str): GameActorEvent }
        .to(Sink.actorRef[GameActorEvent](gameActor, onCompleteMessage = UserLeft(sessionId)))
    }

    //Specifies a source made by materializing an Actor, where the source's values are those that
    //are fed to the Actor, followed by a map that converts them to text messages
    val out: Source[Message,_] = {
      Source.actorRef[Protocol.Response](responseBufferSize, OverflowStrategy.fail)
        .mapMaterializedValue(actorRef => gameActor ! UserJoined(sessionId,username,side,actorRef))
        .map { response: Protocol.Response =>
          import play.api.libs.json._
          TextMessage(Json.stringify(Json.toJson(response))) : Message
        }
    }

    Flow.fromSinkAndSource(in, out)
  }

  //----------------------------------------------------------------------------------
  //DEFINE WEB SERVER ROUTES


  val route = get {
    pathEndOrSingleSlash {
      parameter("username") { username =>
        //Ignore username, just make sure the user provided it
        val _ = username
        getFromFile(Paths.mainPage)
      } ~
      pass {
        complete("Please provide 'username=' in URL")
      }
    } ~
    pathPrefix("js") {
      getFromDirectory(Paths.webjs)
    }
  } ~
  path("playGame") {
    parameter("username") { username =>
      parameter("side") { side =>
        Try(websocketMessageFlow(username,Some(side))) match {
          case Failure(exn) => complete(exn.toString)
          case Success(flow) => handleWebSocketMessages(flow)
        }
      } ~
      pass {
        Try(websocketMessageFlow(username,None)) match {
          case Failure(exn) => complete(exn.toString)
          case Success(flow) => handleWebSocketMessages(flow)
        }
      }
    }
  }

  //----------------------------------------------------------------------------------
  //HERE WE GO!

  val binding = Http().bindAndHandle(route, interface, port)

  binding.onComplete {
    case Failure(e) =>
      log(s"Server http binding failed ${e.getMessage}")
      actorSystem.terminate()
    case Success(binding) =>
      val localAddress = binding.localAddress
      log(s"Server is listening on ${localAddress.getHostName}:${localAddress.getPort}")
      scala.io.StdIn.readLine()
      log("Done")
      actorSystem.terminate()
  }

}
