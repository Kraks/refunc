package refunc

import scala.util.continuations._
import refunc.ast._
import ANFAAM._

object LinearSmallStepUBStack {
  import SmallStepUBStack._

  case class NDCont(cls: List[Clos], argvs: Set[Storable], store: BStore, time: Time, frames: List[Frame])

  case class NDState(e: Expr, env: Env, bstore: BStore, konts: List[Frame], time: Time, ndk: List[NDCont]) {
    def toState: State = State(e, env, bstore, konts, time)
    def tick: Time = (e :: time).take(k)
  }

  def inject(e: Expr, env: Env = Map(), bstore: Store[BAddr, Storable] = Store[BAddr, Storable](Map())): NDState =
    NDState(e, env, bstore, List(), List(), List())

  var trace: List[Expr] = List()

  def step(nds: NDState): Option[NDState] = {
    trace = nds.e::trace
    val new_time = nds.tick
    nds match {
      case NDState(Let(x, ae, e), env, bstore, konts, time, ndk) if isAtomic(ae) =>
        val baddr = allocBind(x, new_time)
        val new_env = env + (x -> baddr)
        val new_store = bstore.update(baddr, atomicEval(ae, env, bstore))
        Some(NDState(e, new_env, new_store, konts, new_time, ndk))

      case NDState(Letrec(bds, body), env, bstore, konts, time, ndk) =>
        val new_env = bds.foldLeft(env)((accenv: Env, bd: B) =>
          accenv + (bd.x -> allocBind(bd.x, new_time)))
        val new_store = bds.foldLeft(bstore)((accbst: BStore, bd: B) =>
          accbst.update(allocBind(bd.x, new_time), atomicEval(bd.e, new_env, accbst)))
        Some(NDState(body, new_env, new_store, konts, new_time, ndk))

      case NDState(Let(x, App(f, ae), e), env, bstore, konts, time, ndk) =>
        val closures = atomicEval(f, env, bstore).toList.asInstanceOf[List[Clos]]
        val Clos(Lam(v, body), c_env) = closures.head
        val frame = Frame(x, e, env)
        val baddr = allocBind(v, new_time)
        val new_env = c_env + (v -> baddr)
        val argvs = atomicEval(ae, env, bstore)
        val new_store = bstore.update(baddr, argvs)
        val new_frames = frame::konts
        val new_ndk = NDCont(closures.tail, argvs, bstore, new_time, new_frames)::ndk
        Some(NDState(body, new_env, new_store, new_frames, new_time, new_ndk))

      case NDState(ae, env, bstore, konts, time, ndk) if isAtomic(ae) =>
        konts match {
          case Nil => ndk match {
            case Nil => None // Halt
            case NDCont(Nil, _, _, _, _)::ndk => 
              Some(NDState(ae, env, bstore, konts, time, ndk)) //NOTE: konts is Nil
            case NDCont(cls, argvs, bstore, time, frames)::ndk => 
              val Clos(Lam(v, body), c_env) = cls.head
              val baddr = allocBind(v, time)
              val new_env = c_env + (v -> baddr)
              val new_store = bstore.update(baddr, argvs)
              Some(NDState(body, new_env, new_store, frames, time, 
                           NDCont(cls.tail, argvs, bstore, time, frames)::ndk))
            case _ => throw new RuntimeException("Invalid NDCont")
          }
          case Frame(x, e, f_env)::konts =>
            val baddr = allocBind(x, new_time)
            val new_env = f_env + (x -> baddr)
            val new_store = bstore.update(baddr, atomicEval(ae, env, bstore))
            Some(NDState(e, new_env, new_store, konts, new_time, ndk))
        }
    }
  }

  def drive(nds: NDState, seen: Set[State]): Set[State] = {
    val s = nds.toState
    val new_seen = if (seen.contains(s)) seen else seen + s
    step(nds) match {
      case None => new_seen
      case Some(nnds) => drive(nnds, new_seen)
    }
  }

  def analyze(e: Expr): Set[State] = {
    trace = List()
    drive(inject(e), Set())
  }

  def analyze(e: Expr, env: Env, bstore: BStore): Set[State] = {
    trace = List()
    drive(inject(e, env, bstore), Set())
  }
}

