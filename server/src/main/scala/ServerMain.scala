package minionsgame.server

import scala.util.{Try,Success,Failure}
import scala.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import com.typesafe.config.ConfigFactory
import java.util.Calendar
import java.text.SimpleDateFormat
import java.security.SecureRandom

import akka.actor.{ActorSystem, Actor, ActorRef, Cancellable, Terminated, Props, Status}
import akka.stream.{ActorMaterializer,OverflowStrategy}
import akka.stream.scaladsl.{Flow,Sink,Source}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes,HttpEntity}
import akka.http.scaladsl.model.ws.{Message,TextMessage}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.event.Logging

import minionsgame.core._
import RichImplicits._

object Paths {
  val applicationConf = "./application.conf"
  val mainPage = "./web/index.html"
  val webjs = "./web/js/"
  val webimg = "./web/img/"
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
  val clientHeartbeatPeriodInSeconds = config.getDouble("akka.http.server.clientHeartbeatRate")


  //GAME ACTOR - singleton actor that maintains the state of the game being played

  sealed trait GameActorEvent
  case class UserJoined(val sessionId: Int, val username: String, val side: Option[Side], val out: ActorRef) extends GameActorEvent
  case class UserLeft(val sessionId: Int) extends GameActorEvent
  case class QueryStr(val sessionId: Int, val queryStr: String) extends GameActorEvent
  case class ShouldEndTurn(val turnNumberToEnd: Int) extends GameActorEvent
  case class ShouldReportTimeLeft() extends GameActorEvent
  case class StartGame() extends GameActorEvent

  private class GameActor(blueSeconds: Double, redSeconds: Double, targetWins: Int, blueMana: Int, redMana: Int, techMana: Int, maps_opt: Option[List[String]], seed_opt: Option[Long]) extends Actor {
    //The actor refs are basically the writer end of a pipe where we can stick messages to go out
    //to the players logged into the server
    var usernameOfSession: Map[Int,String] = Map()
    var userSides: Map[Int,Option[Side]] = Map()
    var userOuts: Map[Int,ActorRef] = Map()
    var allMessages: List[String] = List()
    var teamMessages: SideArray[List[String]] = SideArray.create(List())
    var spectatorMessages: List[String] = List()

    // Random seeds
    val randSeed:Long = {
      seed_opt match {
        case None =>
          val secureRandom = new SecureRandom()
          secureRandom.nextLong()
        case Some(seed) => seed
      }
    }
    val setupRand = Rand(randSeed)
    val spellRands = SideArray.ofArrayInplace(Array(
      Rand(RandUtils.sha256Long(randSeed + "#spell0")),
      Rand(RandUtils.sha256Long(randSeed + "#spell1"))
    ))
    val necroRands = SideArray.ofArrayInplace(Array(
      Rand(RandUtils.sha256Long(randSeed + "#necro0")),
      Rand(RandUtils.sha256Long(randSeed + "#necro1"))
    ))

    //----------------------------------------------------------------------------------
    //GAME AND BOARD SETUP

    val chosenMaps =
      maps_opt match {
        case None =>
          val availableMaps = {
            if(config.getBoolean("app.includeAdvancedMaps"))
              BoardMaps.basicMaps.toList ++ BoardMaps.advancedMaps.toList
            else
              BoardMaps.basicMaps.toList
          }

          if(targetWins > availableMaps.length)
            throw new Exception("Configured for " + targetWins + " boards but only " + availableMaps.length + " available")
          val chosenMaps = setupRand.shuffle(availableMaps).take(targetWins)
          chosenMaps
        case Some(chosenMaps) =>
          val maps = BoardMaps.basicMaps ++ BoardMaps.advancedMaps
          chosenMaps.map { mapName => (mapName, maps(mapName)) }
      }

    val numBoards = chosenMaps.length
    val game = {
      val targetNumWins = targetWins
      val startingMana = SideArray.create(0)
      startingMana(S0) = blueMana * numBoards
      startingMana(S1) = redMana * numBoards
      val techsAlwaysAcquired: Array[Tech] =
        Units.alwaysAcquiredPieces.map { piece => PieceTech(piece.name) }
      val lockedTechs: Array[(Tech,Int)] = {
        if(!config.getBoolean("app.randomizeTechLine"))
          Units.techPieces.zipWithIndex.map { case (piece,i) => (PieceTech(piece.name),i+1) }.toArray
        else {
          //First few techs are always the same
          val numFixedTechs = config.getInt("app.numFixedTechs")
          val fixedTechs = Units.techPieces.zipWithIndex.take(numFixedTechs).toArray
          //Partition remaining ones randomly into two sets of the appropriate size, the first one getting the rounding up
          val randomized = setupRand.shuffle(Units.techPieces.zipWithIndex.drop(numFixedTechs).toList)
          var set1 = randomized.take((randomized.length+1) / 2)
          var set2 = randomized.drop((randomized.length+1) / 2)
          //Sort each set independently
          set1 = set1.sortBy { case (_,origIdx) => origIdx }
          set2 = set2.sortBy { case (_,origIdx) => origIdx }
          //Interleave them
          val set1Opt = set1.map { case (piece,origIdx) => Some((piece,origIdx)) }
          val set2Opt = set2.map { case (piece,origIdx) => Some((piece,origIdx)) }
          val interleaved = set1Opt.zipAll(set2Opt,None,None).flatMap { case (s1,s2) => List(s1,s2) }.flatten.toArray
          (fixedTechs ++ interleaved).map { case (piece,origIdx) => (PieceTech(piece.name),origIdx+1) }
        }
      }
      val extraTechCost = techMana * numBoards
      val extraManaPerTurn = config.getInt("app.extraManaPerTurn")

      val game = Game(
        numBoards = numBoards,
        targetNumWins = targetNumWins,
        startingSide = S0,
        startingMana = startingMana,
        extraTechCost = extraTechCost,
        extraManaPerTurn = extraManaPerTurn,
        techsAlwaysAcquired = techsAlwaysAcquired,
        lockedTechs = lockedTechs
      )
      game
    }
    var gameSequence: Int = 0

    //These get repopulated when empty when we need to draw one
    val specialNecrosRemaining: SideArray[List[String]] = SideArray.create(List())

    //These get repopulated when empty when we need to draw one
    val spellsRemaining: SideArray[List[String]] = SideArray.create(List())
    var nextSpellId: Int = 0
    var spellMap: Map[Int,SpellName] = Map()
    val revealedSpellIds: SideArray[Set[Int]] = SideArray.create(Set())
    val externalInfo: ExternalInfo = ExternalInfo()

    val (boards,boardNames): (Array[Board],Array[String]) = {
      val boardsAndNames = chosenMaps.toArray.map { case (boardName, map) =>
        val state = map()
        val necroNames = SideArray.create(Units.necromancer.name)
        state.resetBoard(necroNames, true, SideArray.create(Map()))

        //Testing
        {
         /*state.spawnPieceInitial(S0, Units.hell_hound.name, Loc(3,3))
         state.spawnPieceInitial(S0, Units.hell_hound.name, Loc(3,3))
         state.spawnPieceInitial(S0, Units.hell_hound.name, Loc(3,3))

         state.spawnPieceInitial(S0, Units.hell_hound.name, Loc(3,4))
         state.spawnPieceInitial(S0, Units.hell_hound.name, Loc(3,4))
         state.spawnPieceInitial(S0, Units.hell_hound.name, Loc(2,4))
         state.spawnPieceInitial(S0, Units.hell_hound.name, Loc(2,4))
         state.spawnPieceInitial(S0, Units.bone_rat.name, Loc(4,3))
         state.spawnPieceInitial(S0, Units.bone_rat.name, Loc(4,3))
         state.spawnPieceInitial(S0, Units.bone_rat.name, Loc(4,3))
         state.spawnPieceInitial(S0, Units.bone_rat.name, Loc(4,4))
         state.spawnPieceInitial(S0, Units.bone_rat.name, Loc(4,4))
         */
         /*state.tiles.foreachi { (loc, tile) =>
            if (tile.terrain == Graveyard) {
              val _ = state.spawnPieceInitial(S0, Units.fallen_angel.name, loc)
            }
          }*/
          /*
          state.spawnPieceInitial(S0, Units.shrieker.name, Loc(5,4))
          state.spawnPieceInitial(S0, Units.witch.name, Loc(6,4))
          state.spawnPieceInitial(S0, Units.fallen_angel.name, Loc(7,4))
          state.spawnPieceInitial(S0, Units.dark_tower.name, Loc(5,5))
          state.spawnPieceInitial(S0, Units.lich.name, Loc(6,5))

          state.spawnPieceInitial(S0, Units.haunt.name, Loc(5,6))

          state.spawnPieceInitial(S1, Units.wight.name, Loc(6,6))

          state.addReinforcementInitial(S0,"zombie")
          state.addReinforcementInitial(S0,"bat")
          state.addReinforcementInitial(S0,"bat")
          state.addReinforcementInitial(S0,"bat")

          state.addReinforcementInitial(S1,"zombie")
          state.addReinforcementInitial(S1,"zombie")
          state.addReinforcementInitial(S1,"bat")
          state.addReinforcementInitial(S1,"bat")
          */
        }

        (Board.create(state), boardName)
      }

      (boardsAndNames.map(_._1),boardsAndNames.map(_._2))
    }
    val boardSequences: Array[Int] = (0 until numBoards).toArray.map { _ => 0}

    //----------------------------------------------------------------------------------
    //TIME LIMITS
    val secondsPerTurn = SideArray.ofArrayInplace(Array(blueSeconds, redSeconds))
    //Server reports time left every this often
    val reportTimePeriod = 5.0
    def getNow(): Double = {
      System.currentTimeMillis.toDouble / 1000.0
    }
    var endOfTurnTime: Option[Double] = None


    //----------------------------------------------------------------------------------

    val timeReportJob: Cancellable = {
      import scala.concurrent.duration._
      import scala.language.postfixOps
      actorSystem.scheduler.schedule(reportTimePeriod seconds, reportTimePeriod seconds) {
        self ! ShouldReportTimeLeft()
      }
    }

    private def broadcastToSpectators(response: Protocol.Response): Unit = {
      userOuts.foreach { case (sid,out) =>
        if(userSides(sid).isEmpty) out ! response
      }
    }
    private def broadcastToSide(response: Protocol.Response, side: Side): Unit = {
      userOuts.foreach { case (sid,out) =>
        if(userSides(sid).contains(side)) out ! response
      }
    }
    private def broadcastAll(response: Protocol.Response): Unit = {
      userOuts.foreach { case (_,out) =>
        out ! response
      }
    }
    private def broadcastMessages(): Unit = {
      broadcastToSide(Protocol.Messages(allMessages, teamMessages(S0)), S0)
      broadcastToSide(Protocol.Messages(allMessages, teamMessages(S1)), S1)
      broadcastToSpectators(Protocol.Messages(allMessages, spectatorMessages))
    }

    private def performAndBroadcastGameActionIfLegal(gameAction: GameAction): Try[Unit] = {
      game.doAction(gameAction).map { case () =>
        gameAction match {
          case PayForReinforcement(_, _) | UnpayForReinforcement(_, _) => ()
          case ChooseSpell(_, _) | UnchooseSpell(_, _) => ()
          case BuyExtraTechAndSpell(_) | UnbuyExtraTechAndSpell(_) => ()
          case PerformTech(_, _) |  UndoTech(_, _) | SetBoardDone(_, _) => ()
          case AddUpcomingSpells(_,_) => ()
          case AddWin(side, boardIdx) =>
            allMessages = allMessages :+ ("GAME: Team " + side.toColorName + " won board " + (boardIdx+1) + "!")
            broadcastMessages()
          case ResignBoard(_) =>
            assertUnreachable()
        }
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

    private def doResetBoard(boardIdx: Int, canMove: Boolean): Unit = {
      Side.foreach { side =>
        if(specialNecrosRemaining(side).isEmpty)
          specialNecrosRemaining(side) = necroRands(side).shuffle(Units.specialNecromancers.toList).map(_.name)
      }
      val necroNames = SideArray.createFn(side => specialNecrosRemaining(side).head)
      Side.foreach { side =>
        specialNecrosRemaining(side) = specialNecrosRemaining(side).tail
      }
      val reinforcements = SideArray.createFn({ side =>
          val unlocked_initiate =
            game.piecesAcquired(side).get(Units.initiate.name) match {
              case None => false
              case Some(techState) => techState.level(side) == TechAcquired
            }
          if(unlocked_initiate) {
            Map(Units.initiate.name -> 1)
          } else {
            Map(Units.acolyte.name -> 1)
          }
      })
      boards(boardIdx).resetBoard(necroNames, canMove, reinforcements)
      broadcastAll(Protocol.ReportResetBoard(boardIdx,necroNames, canMove, reinforcements))
    }

    private def maybeDoEndOfTurn(): Unit = {
      if(game.isBoardDone.forall { isDone => isDone })
        doEndOfTurn()
    }

    private def doAddWin(side: Side, boardIdx: Int): Unit = {
      val gameAction: GameAction = AddWin(side,boardIdx)
      val (_: Try[Unit]) = performAndBroadcastGameActionIfLegal(gameAction)
    }

    private def revealSpellsToSide(side: Side, spellIds: Array[SpellId], revealToSpectators: Boolean = false): Unit = {
      val spellIdsAndNames =
        spellIds.flatMap { spellId =>
          if(revealedSpellIds(side).contains(spellId))
            None
          else
            Some((spellId,spellMap(spellId)))
        }

      spellIdsAndNames.foreach { case (spellId,_) =>
        revealedSpellIds(side) = revealedSpellIds(side) + spellId
      }

      externalInfo.revealSpells(spellIdsAndNames)
      broadcastToSide(Protocol.ReportRevealSpells(spellIdsAndNames),side)
      if(revealToSpectators)
        broadcastToSpectators(Protocol.ReportRevealSpells(spellIdsAndNames))
    }

    private def refillUpcomingSpells(): Unit = {
      //Reveal extra spells beyond the end - players get to look ahead a little in the deck
      val extraSpellsRevealed = 10
      Side.foreach { side =>
        var newUpcomingSpells: Vector[Int] = Vector()

        val numSpellsToAdd = numBoards + 1 + extraSpellsRevealed - game.upcomingSpells(side).length
        for(i <- 0 until numSpellsToAdd) {
          val _ = i
          if(spellsRemaining(side).isEmpty)
            spellsRemaining(side) = spellRands(side).shuffle(Spells.createDeck())

          val spellName = spellsRemaining(side)(0)
          val spellId = nextSpellId
          spellsRemaining(side) = spellsRemaining(side).drop(1)
          nextSpellId += 1
          spellMap = spellMap + (spellId -> spellName)
          newUpcomingSpells = newUpcomingSpells :+ spellId
        }
        revealSpellsToSide(side,newUpcomingSpells.toArray)

        val gameAction: GameAction = AddUpcomingSpells(side,newUpcomingSpells.toArray)
        val (_: Try[Unit]) = performAndBroadcastGameActionIfLegal(gameAction)
      }
    }

    private def doEndOfTurn(): Unit = {
      val oldSide = game.curSide
      val newSide = game.curSide.opp

      //Check win condition and reset boards as needed
      for(boardIdx <- 0 until boards.length) {
        val board = boards(boardIdx)
        if(board.curState.hasWon) {
          doAddWin(oldSide,boardIdx)
          if(game.winner.isEmpty) {
            doResetBoard(boardIdx, true)
          }
        }
      }

      //Accumulate mana on all the boards for the side about to move
      val mana = boards.foldLeft(0) { case (sum,board) =>
        sum + board.curState.manaThisRound(newSide)
      }
      game.addMana(newSide,mana)

      //Automatically tech if it hasn't happened yet, as a convenience
      var moreAutoTechsToBuy = true
      while(moreAutoTechsToBuy && game.numTechsThisTurn < game.extraTechsAndSpellsThisTurn + 1) {
        val idx = game.techLine.indexWhere { techState => techState.level(oldSide) == TechLocked}
        if(idx >= 0) { //-1 if not found
          performAndBroadcastGameActionIfLegal(PerformTech(oldSide,idx)) match {
            case Success(()) => ()
            case Failure(_) =>
              moreAutoTechsToBuy = false
          }
        }
        else {
          moreAutoTechsToBuy = false
        }
      }

      //Automatically choose spells if it hasn't happened yet, as a convenience
      for(boardIdx <- 0 until numBoards) {
        val board = boards(boardIdx)
        if(!board.curState.hasGainedSpell) {
          game.spellsToChoose.find { spellId => !game.spellsChosen.contains(spellId)}.foreach { spellId =>
            val gameAction: GameAction = ChooseSpell(game.curSide,spellId)
            performAndBroadcastGameActionIfLegal(gameAction)
            val boardAction: BoardAction = DoGeneralBoardAction(GainSpell(spellId),"autospell")
            board.doAction(boardAction,externalInfo)
            boardSequences(boardIdx) += 1
            broadcastAll(Protocol.ReportBoardAction(boardIdx,boardAction,boardSequences(boardIdx)))
          }
        }
      }

      //Discard spells to meet sorcery power requirements
      for(boardIdx <- 0 until numBoards) {
        val board = boards(boardIdx)
        val spellIdsToDiscard = board.curState.spellsToAutoDiscardBeforeEndTurn(externalInfo)
        if(spellIdsToDiscard.nonEmpty) {
          revealSpellsToSide(game.curSide.opp,spellIdsToDiscard.toArray, revealToSpectators = true)
          spellIdsToDiscard.foreach { spellId =>
            val boardAction: BoardAction = PlayerActions(List(DiscardSpell(spellId)),"autodiscard")
            boards(boardIdx).doAction(boardAction,externalInfo)
            boardSequences(boardIdx) += 1
            broadcastAll(Protocol.ReportBoardAction(boardIdx,boardAction,boardSequences(boardIdx)))
          }
        }
      }

      game.endTurn()
      boards.foreach { board => board.endTurn() }
      broadcastAll(Protocol.ReportNewTurn(newSide))

      refillUpcomingSpells()

      for(boardIdx <- 0 until boards.length) {
        val board = boards(boardIdx)
        if(board.curState.hasWon) {
          if(game.winner.isEmpty) {
            doAddWin(newSide,boardIdx)
            if(game.winner.isEmpty) {
              doResetBoard(boardIdx, false)
            }
          }
        }
      }

      //Schedule the next end of turn
      scheduleEndOfTurn(game.turnNumber)
      game.winner match {
        case Some(winner) =>
          allMessages = allMessages :+ ("GAME: Team " + winner.toColorName + " won the game!")
        case None =>
          game.newTechsThisTurn.foreach { case (side,tech) =>
            allMessages = allMessages :+ ("GAME: Team " + side.toColorName + " acquired new tech: " + tech.displayName)
          }
          allMessages = allMessages :+ ("GAME: Beginning " + newSide.toColorName + " team turn (turn #" + game.turnNumber + ")")
      }
      broadcastMessages()
    }

    private def getTimeLeftEvent(): Option[Protocol.Response] = {
      if(game.winner.isEmpty) {
        val timeLeft = endOfTurnTime.map { endOfTurnTime => endOfTurnTime - getNow() }
        Some(Protocol.ReportTimeLeft(timeLeft))
      }
      else {
        val (_: Boolean) = timeReportJob.cancel()
        None
      }
    }

    private def maybeBroadcastTimeLeft(): Unit = {
      getTimeLeftEvent().foreach { response =>
        broadcastAll(response)
      }
    }

    private def scheduleEndOfTurn(turnNumber: Int): Unit = {
      import scala.concurrent.duration._
      import scala.language.postfixOps
      endOfTurnTime = Some(getNow() + secondsPerTurn(game.curSide))
      maybeBroadcastTimeLeft()
      val (_: Cancellable) = actorSystem.scheduler.scheduleOnce(secondsPerTurn(game.curSide) seconds) {
        //Make sure to do this via sending event to self, rather than directly, to
        //take advantage of the actor's synchronization
        self ! ShouldEndTurn(turnNumber)
      }
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
              else if(game.winner.nonEmpty)
                out ! Protocol.QueryError("Game is over")
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
                    boards(boardIdx).tryLegality(boardAction,externalInfo).flatMap { case () =>
                      //And if so, go ahead and recover the cost of the unit
                      val gameAction: GameAction = UnpayForReinforcement(side,pieceName)
                      performAndBroadcastGameActionIfLegal(gameAction)
                    }
                  case GainSpellUndo(spellId,_) =>
                    //Check ahead of time if it's legal
                    boards(boardIdx).tryLegality(boardAction,externalInfo).flatMap { case () =>
                      //And if so, go ahead and recover the cost of the unit
                      val gameAction: GameAction = UnchooseSpell(side,spellId)
                      performAndBroadcastGameActionIfLegal(gameAction)
                    }
                  case DoGeneralBoardAction(generalBoardAction,_) =>
                    generalBoardAction match {
                      case BuyReinforcement(pieceName) =>
                        //Pay for the cost of the unit
                        val gameAction: GameAction = PayForReinforcement(side,pieceName)
                        performAndBroadcastGameActionIfLegal(gameAction)
                      case GainSpell(spellId) =>
                        //Check ahead of time if it's legal
                        boards(boardIdx).tryLegality(boardAction,externalInfo).flatMap { case () =>
                          //Make sure the spell can be chosen
                          val gameAction: GameAction = ChooseSpell(side,spellId)
                          performAndBroadcastGameActionIfLegal(gameAction)
                        }
                    }
                }

                specialResult.flatMap { case () => boards(boardIdx).doAction(boardAction,externalInfo) } match {
                  case Failure(e) =>
                    out ! Protocol.QueryError(e.getLocalizedMessage)
                  case Success(()) =>
                    //When someone plays or discards a spell legally/successfully, reveal it to the other side.
                    boardAction match {
                      case PlayerActions(actions,_) =>
                        actions.foreach {
                          case PlaySpell(spellId,_) => revealSpellsToSide(game.curSide.opp,Array(spellId), revealToSpectators = true)
                          case DiscardSpell(spellId) => revealSpellsToSide(game.curSide.opp,Array(spellId), revealToSpectators = true)
                          case (_: Movements) | (_: Attack) | (_: Spawn) | (_: ActivateTile) | (_: ActivateAbility) | (_: Teleport) => ()
                        }
                      case (_: LocalPieceUndo) | (_: SpellUndo) | (_: BuyReinforcementUndo) | (_: GainSpellUndo) | (_: DoGeneralBoardAction) => ()
                    }

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
              if(game.winner.nonEmpty)
                out ! Protocol.QueryError("Game is over")
              else if(game.curSide != side)
                out ! Protocol.QueryError("Currently the other team's turn")
              else {
                //Some game actions are special and are meant to be server -> client only, or need extra checks
                val specialResult: Try[Unit] = gameAction match {
                  case (_: PerformTech) | (_: UndoTech) | (_: SetBoardDone) => Success(())
                  case BuyExtraTechAndSpell(_) =>
                    refillUpcomingSpells()
                    Success(())
                  case UnbuyExtraTechAndSpell(_) => Success(())
                  case ResignBoard(boardIdx) =>
                    //Check ahead of time if it's legal
                    game.tryIsLegal(gameAction).map { case () =>
                      //And if so, reset the board
                      doResetBoard(boardIdx, true)
                      allMessages = allMessages :+ ("GAME: Team " + game.curSide.toColorName + " resigned board " + (boardIdx+1) + "!")
                      broadcastMessages()
                    }
                  case (_: PayForReinforcement) | (_: UnpayForReinforcement) | (_: AddWin) | (_: AddUpcomingSpells) |
                      (_: ChooseSpell) | (_: UnchooseSpell) =>
                    Failure(new Exception("Only server allowed to send this action"))
                }
                specialResult.flatMap { case () => game.doAction(gameAction) } match {
                  case Failure(e) =>
                    out ! Protocol.QueryError(e.getLocalizedMessage)
                  case Success(()) =>
                    gameSequence += 1
                    out ! Protocol.OkGameAction(gameSequence)
                    broadcastAll(Protocol.ReportGameAction(gameAction,gameSequence))
                    game.winner.foreach { winner =>
                      allMessages = allMessages :+ ("GAME: Team " + winner.toColorName + " won the game!")
                      broadcastMessages()
                    }
                    maybeDoEndOfTurn()
                }
              }
          }

        case Protocol.Chat(username, side, allChat, message) =>
          if(allChat) {
            allMessages = allMessages :+ (username + ": " + message)
          } else {
            side match {
              case None => spectatorMessages = spectatorMessages :+ (username + ": " + message)
              case Some(side) => teamMessages(side) = teamMessages(side) :+ (username + ": " + message)
            }
          }
          broadcastMessages()
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

    def joinedOrLeftMessage(username : String, side : Option[Side], joined : Boolean) : String = {
      val sideStr = side match {
        case None => "as a spectator"
        case Some(side) => "team " + side.toColorName
      }
      val joinedOrLeft = if(joined) "joined" else "left"
      return "GAME: " + username + " " + joinedOrLeft + " " + sideStr
    }

    override def receive: Receive = {
      case UserJoined(sessionId, username, side, out) =>
        usernameOfSession = usernameOfSession + (sessionId -> username)
        userSides = userSides + (sessionId -> side)
        userOuts = userOuts + (sessionId -> out)

        allMessages = allMessages :+ joinedOrLeftMessage(username, side, true)

        out ! Protocol.Version(CurrentVersion.version)
        out ! Protocol.ClientHeartbeatRate(periodInSeconds=clientHeartbeatPeriodInSeconds)

        val spellIds = side match {
          case None => revealedSpellIds(S0).intersect(revealedSpellIds(S1))
          case Some(side) => revealedSpellIds(side)
        }
        val spellIdsAndNames = spellIds.toArray.map { spellId => (spellId,spellMap(spellId)) }
        out ! Protocol.ReportRevealSpells(spellIdsAndNames)

        out ! Protocol.Initialize(game, boards.map { board => board.toSummary()}, boardNames, boardSequences.clone())
        getTimeLeftEvent().foreach { response => out ! response }
        broadcastAll(Protocol.UserJoined(username,side))
        broadcastMessages()
        log("UserJoined: " + username + " Side: " + side)

      case UserLeft(sessionId) =>
        if(usernameOfSession.contains(sessionId)) {
          val username = usernameOfSession(sessionId)
          val side = userSides(sessionId)
          allMessages = allMessages :+ joinedOrLeftMessage(username, side, false)
          broadcastAll(Protocol.UserLeft(username,side))
          broadcastMessages()
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
            case Failure(err) => out ! Protocol.QueryError("Could not parse as json: " + err.getLocalizedMessage)
            case Success(json) =>
              json.validate[Protocol.Query] match {
                case (e: JsError) => out ! Protocol.QueryError("Could not parse as query: " + JsError.toJson(e).toString())
                case (s: JsSuccess[Protocol.Query]) =>
                  val query = s.get
                  handleQuery(query, out, userSides(sessionId))
              }
          }
        }
      case ShouldEndTurn(turnNumberToEnd) =>
        if(game.turnNumber == turnNumberToEnd && game.winner.isEmpty) {
          doEndOfTurn()
        }

      case ShouldReportTimeLeft() =>
        maybeBroadcastTimeLeft()

      case StartGame() =>
        refillUpcomingSpells()
        game.startGame()
    }

  }

  var games = Map[String, ActorRef]()
  var passwords = Map[String, Option[String]]()

  //----------------------------------------------------------------------------------
  //WEBSOCKET MESSAGE HANDLING

  val nextSessionId = new AtomicInteger()

  def websocketMessageFlow(gameid: String, username: String, sideStr: Option[String]) : Flow[Message, Message, _] = {
    val side: Option[Side] = sideStr match {
      case Some("0") => Some(S0)
      case Some("1") => Some(S1)
      case Some(s) => throw new Exception("Invalid side: " + s)
      case None => None
    }
    val gameActor = games(gameid)
    val sessionId = nextSessionId.getAndIncrement()

    //Create output stream for the given user
    val responseBufferSize = 128 //Buffer messages to the user before failing

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
      parameter("game".?) { gameid_opt =>
        parameter("username".?) { username =>
          parameter("password".?) { password =>
            gameid_opt match {
              case None => complete("Please provide 'game=' in URL")
              case Some(gameid) =>
                username match {
                  case None => complete("Please provide 'username=' in URL")
                  case Some(_) =>
                    passwords.get(gameid) match {
                      case None => complete(s"Game $gameid does not exist")
                      case Some(game_password) =>
                        (password, game_password) match {
                          case (None, Some(_)) => complete(gameid + " is password-protected; please provide 'password=' in URL")
                          case (_, None) => getFromFile(Paths.mainPage)
                          case (Some(x), Some(y)) =>
                            if(x==y) getFromFile(Paths.mainPage)
                            else complete(s"Wrong password for $gameid")
                        }
                    }
                }
            }
          }
        }
      }
    } ~
    pathPrefix("js") {
      getFromDirectory(Paths.webjs)
    } ~
    pathPrefix("img") {
      getFromDirectory(Paths.webimg)
    }
  } ~
  path("playGame") {
    parameter("username") { username =>
      parameter("game") { gameid =>
        parameter("side".?) { side =>
          Try(websocketMessageFlow(gameid,username,side)) match {
            case Failure(exn) => complete(exn.getLocalizedMessage)
            case Success(flow) => handleWebSocketMessages(flow)
          }
        }
      }
    }
  } ~
  path("newGame") {
    get {
      val game = "game" + (games.size.toString)
      val blueSecondsPerTurn = config.getDouble("app.s0SecondsPerTurn")
      val redSecondsPerTurn = config.getDouble("app.s1SecondsPerTurn")
      val targetNumWins = config.getInt("app.targetNumWins")
      val blueStartingMana = config.getInt("app.s0StartingManaPerBoard")
      val redStartingMana = config.getInt("app.s1StartingManaPerBoard")
      val extraTechCost = config.getInt("app.extraTechCostPerBoard")

      val map_html =
        (BoardMaps.basicMaps.toList ++ BoardMaps.advancedMaps.toList).map { case (mapName, _) =>
          s"""<p><label>$mapName</label><input type=checkbox name=map value="$mapName"></input><br>"""
        }.mkString("\n")
      complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
        s"""
        <head>
          <style type="text/css">
            form  { display: table;      }
            p     { display: table-row;  }
            label { display: table-cell; }
            input { display: table-cell; }
          </style>
        </head>
        <body>
          <form method=post>
            <p><label>Game name </label><input type="text" name="game" value=$game></input>
            <p><label>Password (optional) </label><input type="text" name="password"></input>
            <p><label>Random seed (optional) </label><input type="text" name="seed"></input><br>

            <p><label>Blue seconds per turn </label><input type="text" name=blueSeconds value=$blueSecondsPerTurn></input><br>
            <p><label>Red seconds per turn </label><input type="text" name=redSeconds value=$redSecondsPerTurn></input><br>

            <p><label>Points to win </label><input type="text" name=targetWins value=$targetNumWins></input><br>

            <p><label>Blue starting mana per board&nbsp&nbsp </label><input type="text" name=blueMana value=$blueStartingMana></input><br>
            <p><label>Red starting mana per board </label><input type="text" name=redMana value=$redStartingMana></input><br>

            <p><label>Tech cost per board </label><input type="text" name=techMana value=$extraTechCost></input><br>

            <p>&nbsp
            <p><label>Maps (optional)</label>
            $map_html

            <p><input type="submit" value="Start Game"></input>
          </form>
        </body>
        """
        ))
      } ~ post {
        formFields(('game, 'password, 'seed)) { (gameid, password, seed) =>
          formFields(('blueSeconds.as[Double], 'redSeconds.as[Double], 'targetWins.as[Int])) { (blueSeconds, redSeconds, targetWins) =>
            formFields(('blueMana.as[Int], 'redMana.as[Int], 'techMana.as[Int], 'map.*)) { (blueMana, redMana, techMana, maps) =>
              passwords.get(gameid) match {
                case Some(_) =>
                  complete(s"""A game named "$gameid" already exists; pick a different name""")
                case None =>
                  val seed_opt = if(seed=="") None else Some(seed.toLong)
                  val maps_opt = if(maps.isEmpty) None else Some(maps.toList)
                  val game_actor = actorSystem.actorOf(Props(classOf[GameActor], blueSeconds, redSeconds, targetWins, blueMana, redMana, techMana, maps_opt, seed_opt))
                  passwords = passwords + (gameid -> (if(password == "") None else Some(password)))
                  games = games + (gameid -> game_actor)
                  println("Created game " + gameid)
                  complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`,
                    s"""
                    Created game $gameid

                    password=$password
                    seed=$seed_opt
                    blueSeconds=$blueSeconds
                    redSeconds=$redSeconds
                    targetWins=$targetWins
                    blueMana=$blueMana
                    redMana=$redMana
                    techMana=$techMana
                    maps=$maps_opt
                    seed=$seed_opt
                    """
                    ))
              }
            }
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
