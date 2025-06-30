package moe.karla.endpoint




import zio.*
import zio.http.*
import moe.karla.miscellaneous.ProgramState



object BasicEndpoint:
  
  def routes = Routes(

    Method.GET / "" -> Handler.fromResponse(Response.redirect(URL.root / "static" / "index.html", isPermanent = true)),

    Method.GET / "health-check" -> Handler.text("health"),

  )
