package minionsgame.core
import scala.collection.immutable.Map
import scala.util.{Try,Success,Failure}

object Units {
  private def createPieceStats(
    name : String,
    shortDisplayName: String = "",
    displayName: String = "",
    attackEffect : Option[TargetEffect],
    defense : Option[Int],
    moveRange : Int,
    attackRange : Int,
    attackRangeVsFlying: Int = 0,
    numAttacks : Int = 1,
    cost : Int,
    rebate : Int,
    isNecromancer : Boolean = false,
    isFlying : Boolean = false,
    isLumbering : Boolean = false,
    isPersistent : Boolean = false,
    isEldritch : Boolean = false,
    isWailing : Boolean = false,
    canBlink : Boolean = false,
    canHurtNecromancer : Boolean = true,
    swarmMax : Int = 1,
    spawnRange : Option[Int] = None,
    extraMana : Int = 0,
    extraSorceryPower : Int = 0,
    deathSpawn : Option[PieceName] = None,
    abilities : Map[String,PieceAbility] = Map.empty
  ) : PieceStats = {
    PieceStats(
      name = name,
      shortDisplayName = (if(shortDisplayName == "") name.capitalize else shortDisplayName),
      displayName = (if(displayName == "") name.capitalize else displayName),
      isBaseStats = true,
      attackEffect = attackEffect,
      defense = defense,
      moveRange = moveRange,
      attackRange = attackRange,
      attackRangeVsFlying = Math.max(attackRange,attackRangeVsFlying),
      numAttacks = numAttacks,
      cost = cost,
      rebate = rebate,
      isNecromancer = isNecromancer,
      isFlying = isFlying,
      isLumbering = isLumbering,
      isPersistent = isPersistent,
      isEldritch = isEldritch,
      isWailing = isWailing,
      canBlink = canBlink,
      canHurtNecromancer = canHurtNecromancer,
      swarmMax = swarmMax,
      spawnRange = spawnRange,
      extraMana = extraMana,
      extraSorceryPower = extraSorceryPower,
      deathSpawn = deathSpawn,
      abilities = abilities
    )
  }

  val necromancer = createPieceStats(
    name = "necromancer",
    shortDisplayName = "Necro",
    cost = 0,
    rebate = 0,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Unsummon),
    defense = Some(7),
    spawnRange = Some(1),
    isPersistent = true,
    isNecromancer = true,
    extraMana = 3
  )
  val swarm_necromancer = createPieceStats(
    name = "swarm_necromancer",
    shortDisplayName = "SNec",
    displayName = "Swarm Necromancer",
    cost = 0,
    rebate = 0,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Unsummon),
    defense = Some(7),
    spawnRange = Some(1),
    isPersistent = true,
    swarmMax = 3,
    isNecromancer = true,
    extraMana = 1,
  )
  val arcane_necromancer = createPieceStats(
    name = "arcane_necromancer",
    shortDisplayName = "ArcNec",
    displayName = "Arcane Necromancer",
    cost = 0,
    rebate = 0,
    moveRange = 1,
    attackRange = 0,
    attackEffect = None,
    defense = Some(7),
    spawnRange = Some(1),
    isPersistent = true,
    isNecromancer = true,
    extraMana = 5,
  )
  val mounted_necromancer = createPieceStats(
    name = "mounted_necromancer",
    shortDisplayName = "MntNec",
    displayName = "Mounted Necromancer",
    cost = 0,
    rebate = 0,
    moveRange = 2,
    attackRange = 1,
    attackEffect = Some(Unsummon),
    defense = Some(7),
    spawnRange = Some(1),
    isPersistent = true,
    isNecromancer = true,
    extraMana = 3,
  )
  val ranged_necromancer = createPieceStats(
    name = "ranged_necromancer",
    shortDisplayName = "RngNec",
    displayName = "Ranged Necromancer",
    cost = 0,
    rebate = 0,
    moveRange = 1,
    attackRange = 3,
    attackEffect = Some(Unsummon),
    defense = Some(10),
    spawnRange = Some(1),
    isPersistent = true,
    isNecromancer = true,
    extraMana = 3,
  )
  val deadly_necromancer = createPieceStats(
    name = "deadly_necromancer",
    shortDisplayName = "DedNec",
    displayName = "Deadly Necromancer",
    cost = 0,
    rebate = 0,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Kill),
    defense = Some(10),
    spawnRange = Some(1),
    isPersistent = true,
    isNecromancer = true,
    extraMana = 3,
    canHurtNecromancer = false,
  )
  val immortal_necromancer = createPieceStats(
    name = "immortal_necromancer",
    shortDisplayName = "ImmNec",
    displayName = "Immortal Necromancer",
    cost = 0,
    rebate = 0,
    moveRange = 1,
    isFlying = true,
    attackRange = 1,
    attackEffect = Some(Unsummon),
    defense = None,
    spawnRange = Some(1),
    isPersistent = true,
    isNecromancer = true,
    extraMana = 3,
  )
  val summoner_necromancer = createPieceStats(
    name = "summoner_necromancer",
    shortDisplayName = "SumNec",
    displayName = "Summoner Necromancer",
    cost = 0,
    rebate = 0,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Unsummon),
    defense = Some(7),
    spawnRange = Some(2),
    isPersistent = true,
    isNecromancer = true,
    extraMana = 3,
  )

  val zombie = createPieceStats(
    name = "zombie",
    cost = 2,
    rebate = 0,
    moveRange = 1,
    isLumbering = true
    attackRange = 1,
    attackEffect = Some(Damage(1)),
    defense = Some(2),
  )

  val acolyte = createPieceStats(
    name = "acolyte",
    cost = 5,
    rebate = 3,
    moveRange = 2,
    attackRange = 0,
    attackEffect = None,
    defense = Some(2),
    spawnRange = Some(1)
  )

  val spire = createPieceStats(
    name = "spire",
    cost = 5,
    rebate = 2,
    moveRange = 0,
    attackRange = 1,
    attackEffect = Some(Damage(5)),
    defense = Some(4),
    spawnRange = Some(1),
  )

  val initiate = createPieceStats(
    name = "initiate",
    cost = 5,
    rebate = 3,
    moveRange = 2,
    isLumbering = true
    attackRange = 1,
    attackEffect = Some(Damage(5)),
    defense = Some(5),
    spawnRange = Some(1),
  )

  val skeleton = createPieceStats(
    name = "skeleton",
    cost = 4,
    rebate = 2,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Damage(5)),
    defense = Some(2),
  )

  val serpent = createPieceStats(
    name = "serpent",
    cost = 5,
    rebate = 3,
    moveRange = 2,
    attackRange = 1,
    attackEffect = Some(Damage(3)),
    defense = Some(1),
    abilities = Map(MoveFlood.name -> MoveFlood)
  )

  val bat = createPieceStats(
    name = "bat",
    cost = 4,
    rebate = 2,
    moveRange = 3,
    isFlying = true
    attackRange = 1,
    attackEffect = Some(Damage(1)),
    defense = Some(1),
  )

  val ghost = createPieceStats(
    name = "ghost",
    cost = 3,
    rebate = 0,
    moveRange = 1,
    isFlying = true,
    attackRange = 1,
    attackEffect = Some(Damage(1)),
    defense = Some(4),
    isPersistent = true
  )

  val wight = createPieceStats(
    name = "wight",
    cost = 5,
    rebate = 0,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Damage(3)),
    defense = Some(3),
    deathSpawn = Some(zombie.name),
    abilities = Map("doubleattack" -> SelfEnchantAbility(
      name = "doubleattack",
      displayName = "Double Strike (sorcery)",
      desc = List("Pay 1 sorcery power: Attacks twice"),
      isSorcery = true,
      tryIsUsableNow = { (piece: Piece) =>
        if(piece.actState >= Spawning) Failure(new Exception("Piece has already acted or cannot act this turn"))
        else Success(())
      },
      mod = PieceModWithDuration(PieceMods.DoubleAttack,turnsLeft = Some(1))
    ))
  )

  val haunt = createPieceStats(
    name = "haunt",
    cost = 5,
    rebate = 1,
    moveRange = 0,
    attackRange = 3,
    attackEffect = Some(Damage(2)),
    defense = Some(2),
    canBlink = true,
    abilities = Map(MoveEarthquake.name -> MoveEarthquake)
  )

  val shrieker = createPieceStats(
    name = "shrieker",
    cost = 7,
    rebate = 4,
    moveRange = 2,
    attackRange = 1,
    attackEffect = Some(Damage(1)),
    numAttacks = 3,
    defense = Some(2),
    abilities = Map("rangetwo" -> SelfEnchantAbility(
      name = "rangetwo",
      displayName = "Range Two (sorcery)",
      desc = List("Pay 1 sorcery power: Range 2"),
      isSorcery = true,
      tryIsUsableNow = { (piece: Piece) =>
        piece.actState match {
          case DoneActing | Spawning => Failure(new Exception("Piece has already finished its turn"))
          case Moving(_) | Attacking(_) => Success(())
        }
      },
      mod = PieceModWithDuration(PieceMods.RangeTwo, turnsLeft = Some(1))
    ))
  )

  val warg = createPieceStats(
    name = "warg",
    cost = 7,
    rebate = 5,
    moveRange = 3,
    attackRange = 1,
    attackEffect = Some(Damage(2)),
    defense = Some(3),
  )

  val dark_tower = createPieceStats(
    name = "dark_tower",
    shortDisplayName = "DTower",
    displayName = "Dark Tower",
    cost = 7,
    rebate = 0,
    moveRange = 0,
    attackRange = 2,
    attackEffect = Some(Damage(5)),
    defense = Some(4),
    spawnRange = Some(1),
    deathSpawn = Some(spire.name),
    abilities = Map(SpawnZombiesAbility.name -> SpawnZombiesAbility),
  )

  val bone_rat = createPieceStats(
    name = "bone_rat",
    shortDisplayName = "BnRat",
    displayName = "Bone Rat",
    cost = 2,
    rebate = 1,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Damage(1)),
    defense = Some(1),
    swarmMax = 3,
    abilities = Map("unsummonattack" -> SelfEnchantAbility(
      name = "unsummonattack",
      displayName = "Unsummon Strike (sorcery)",
      desc = List("Pay 1 sorcery power: Attack unsummons"),
      isSorcery = true,
      tryIsUsableNow = { (piece: Piece) =>
        if(piece.actState >= Spawning) Failure(new Exception("Piece has already acted or cannot act this turn"))
        else Success(())
      },
      mod = PieceModWithDuration(PieceMods.UnsummonAttack,turnsLeft = Some(1))
    ))
  )

  val witch = createPieceStats(
    name = "witch",
    cost = 4,
    rebate = 0,
    moveRange = 1,
    attackRange = 3,
    attackEffect = Some(Damage(1)),
    defense = Some(2),
    swarmMax = 3,
  )

  val vampire = createPieceStats(
    name = "vampire",
    cost = 6,
    rebate = 3,
    moveRange = 1,
    isFlying = true,
    attackRange = 1,
    attackEffect = Some(Damage(1)),
    numAttacks = 2,
    defense = Some(5),
    isPersistent = true,
    abilities = Map("movethree" -> SelfEnchantAbility(
      name = "movethree",
      displayName = "Move Three (sorcery)",
      desc = List("Pay 1 sorcery power: Move 3"),
      isSorcery = true,
      tryIsUsableNow = { (piece: Piece) =>
        piece.actState match {
          case DoneActing | Spawning | Attacking(_) => Failure(new Exception("Piece has already acted or attacked this turn"))
          case Moving(_) => Success(())
        }
      },
      mod = PieceModWithDuration(PieceMods.MoveThree, turnsLeft = Some(1))
    ))
  )

  val mummy = createPieceStats(
    name = "mummy",
    cost = 8,
    rebate = 0,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Damage(5)),
    defense = Some(8),
    spawnRange = Some(1),
    deathSpawn = Some(initiate.name),
  )

  val lich = createPieceStats(
    name = "lich",
    cost = 8,
    rebate = 0,
    moveRange = 1,
    isLumbering = true,
    attackRange = 3,
    attackEffect = Some(Kill),
    defense = Some(3),
    deathSpawn = Some(skeleton.name),
    canHurtNecromancer = false,
  )

  val void = createPieceStats(
    name = "void",
    cost = 5,
    rebate = 3,
    moveRange = 2,
    attackRange = 2,
    attackEffect = Some(Unsummon),
    defense = Some(2),
    canBlink = true,
    abilities = Map(MoveWhirlwind.name -> MoveWhirlwind)
  )

  val hell_hound = createPieceStats(
    name = "hell_hound",
    shortDisplayName = "Hound",
    displayName = "Hell Hound",
    cost = 4,
    rebate = 2,
    moveRange = 3,
    attackRange = 1,
    attackEffect = Some(Damage(1)),
    defense = Some(2),
    swarmMax = 3,
    abilities = Map(MoveFirestorm.name -> MoveFirestorm)
  )

  val wraith = createPieceStats(
    name = "wraith",
    cost = 8,
    rebate = 3,
    moveRange = 1,
    isFlying = true,
    attackRange = 1,
    attackEffect = Some(Damage(3)),
    defense = Some(13),
    isPersistent = true,
  )

  val fiend = createPieceStats(
    name = "fiend",
    cost = 9,
    rebate = 3,
    moveRange = 1,
    attackRange = 1,
    attackEffect = Some(Damage(1)),
    numAttacks = 9,
    defense = Some(9),
    abilities = Map("airstrike" -> SelfEnchantAbility(
      name = "airstrike",
      displayName = "Air Strike (sorcery)",
      desc = List("Pay 1 sorcery power: Attack range 3 vs flying"),
      isSorcery = true,
      tryIsUsableNow = { (piece: Piece) =>
        if(piece.actState >= Spawning) Failure(new Exception("Piece has already acted or cannot act this turn"))
        else Success(())
      },
      mod = PieceModWithDuration(PieceMods.AirStrike, turnsLeft = Some(1))
    ))
  )

  val banshee = createPieceStats(
    name = "banshee",
    cost = 6,
    rebate = 3,
    moveRange = 2,
    attackRange = 1,
    attackEffect = Some(Kill),
    defense = Some(2),
    canHurtNecromancer = false,
    abilities = Map(KillAdjacentAbility.name -> KillAdjacentAbility)
  )

  val elemental = createPieceStats(
    name = "elemental",
    shortDisplayName = "Element",
    cost = 9,
    rebate = 4,
    moveRange = 1,
    attackRange = 3,
    attackEffect = Some(Damage(3)),
    defense = Some(3),
    abilities = Map(MoveTerrain.name -> MoveTerrain)
  )

  val fallen_angel = createPieceStats(
    name = "fallen_angel",
    shortDisplayName = "FAngel",
    displayName = "Fallen Angel",
    cost = 13,
    rebate = 6,
    moveRange = 2,
    isFlying = true,
    attackRange = 2,
    attackEffect = Some(Damage(1)),
    numAttacks = 4,
    defense = Some(7),
    spawnRange = Some(1),
  )

  val shadowlord = createPieceStats(
    name = "shadowlord",
    shortDisplayName = "SLord",
    cost = 13,
    rebate = 6,
    moveRange = 2,
    isFlying = true,
    attackRange = 1,
    attackEffect = Some(Damage(7)),
    defense = Some(13),
    isPersistent = true,
  )

  //All pieces
  val pieces: Array[PieceStats] = Array(
    necromancer,
    arcane_necromancer,
    ranged_necromancer,
    mounted_necromancer,
    immortal_necromancer,
    deadly_necromancer,
    swarm_necromancer,
    //summoner_necromancer,
    zombie, acolyte, spire,
    initiate, skeleton, serpent, bat, ghost, wight, haunt, shrieker,
    warg, dark_tower, witch, vampire, mummy, lich, bone_rat, void, hell_hound,
    wraith, fiend, banshee, elemental, fallen_angel, shadowlord
  )

  //Necromancers awarded after a board resets
  val specialNecromancers: Array[PieceStats] = Array(
    arcane_necromancer,
    ranged_necromancer,
    mounted_necromancer,
    immortal_necromancer,
    deadly_necromancer,
    swarm_necromancer,
    //summoner_necromancer,
  )

  //Pieces that start off buyable
  val alwaysAcquiredPieces: Array[PieceStats] = Array(
    zombie,
    acolyte,
    spire
  )

  //Pieces that need to be unlocked, in order
  val techPieces: Array[PieceStats] = Array(
    initiate,
    skeleton,
    serpent,
    bat,
    ghost,
    wight,
    haunt,
    shrieker,
    warg,
    dark_tower,
    bone_rat,
    witch,
    vampire,
    mummy,
    lich,
    void,
    hell_hound,
    wraith,
    fiend,
    banshee,
    elemental,
    fallen_angel,
    shadowlord
  )

  //Generally, we store and send the PieceName everywhere in the protocol, since unlike a PieceStats it's easily serialized.
  //This is the global map that everything uses to look up the stats again from the name.
  val pieceMap: Map[PieceName,PieceStats] = pieces.groupBy(piece => piece.name).mapValues { pieces =>
    assert(pieces.length == 1)
    pieces.head
  }

}
