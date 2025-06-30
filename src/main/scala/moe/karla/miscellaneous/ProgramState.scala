package moe.karla.miscellaneous

import zio.ZLayer
import zio.Ref



class ProgramState(
  val downValve: Ref[Int]
)


object ProgramState:
  val layer = ZLayer.fromZIO(Ref.make(0).map(ProgramState(_)))