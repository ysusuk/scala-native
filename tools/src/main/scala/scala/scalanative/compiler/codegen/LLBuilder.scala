package scala.scalanative
package compiler
package codegen

import scala.collection.mutable.{UnrolledBuffer => Buf, Map}
import util.{unsupported, unreachable, sh, Show}
import util.Show.{Sequence => s, Indent => i, Unindent => ui, Repeat => r, Newline => nl, Result => Res}
import nir._

final class LLBuilder {
  private val edges  = Map.empty[Local, Buf[(Local, Seq[Res])]]
  private val blocks = Buf.empty[LLBlock]

  private var name: Local               = _
  private var params: Seq[(Local, Res)] = _
  private var insts: Buf[Res]           = _
  private var isEntry: Boolean          = _

  private def edge(to: Local, values: Seq[Res] = Seq.empty): Unit = {
    if (!edges.contains(to)) {
      edges(to) = Buf.empty
    }
    edges(to) += ((name, values))
  }

  def start(): Unit = {
    edges.clear()
    blocks.clear()
  }

  def end(): Seq[Res] = {
    val buf = Buf.empty[Res]

    blocks.foreach {
      case LLBlock(name, params, insts, isEntry) =>
        buf += sh"${nl(name)}:"

        if (!isEntry) {
          val preds = edges.getOrElse(name, Buf.empty)
          params.zipWithIndex.map {
            case ((name, ty), n) =>
              val froms = preds.map {
                case (from, vals) =>
                  sh"[${vals(n)}, %$from]"
              }

              buf += i(sh"%$name = phi $ty ${r(froms, sep = ", ")}")
          }
        }

        insts.foreach { inst =>
          buf += i(inst)
        }
    }

    buf
  }

  def block(name: Local,
            params: Seq[(Local, Res)] = Seq(),
            isEntry: Boolean = false): Unit = {
    this.name = name
    this.params = params
    this.insts = Buf.empty[Res]
    this.isEntry = isEntry
    blocks += LLBlock(name, params, this.insts, isEntry)
  }

  def inst(op: Res): Unit =
    insts += op

  def inst(name: Local, op: Res): Unit =
    insts += sh"%$name = $op"

  def invoke(sig: Res): Unit = {
    insts += sh"call $sig"
  }

  def invoke(name: Local, sig: Res): Unit = {
    insts += sh"%$name = call $sig"
  }

  def unreachable(): Unit =
    insts += sh"unreachable"

  def ret(value: Res): Unit =
    insts += sh"ret $value"

  def resume(value: Res): Unit = {
    insts += sh"resume $value"
  }

  def jump(next: Local): Unit = {
    insts += sh"br label %$next"
    edge(next)
  }

  def jump(next: Local, values: Seq[Res]): Unit = {
    insts += sh"br label %$next"
    edge(next, values)
  }

  def branch(cond: Res, thenp: Local, elsep: Local): Unit = {
    insts += sh"br $cond, label %$thenp, label %$elsep"
    edge(thenp)
    edge(elsep)
  }

  def switch(scrut: Res, default: Local, cases: Seq[(Res, Local)]): Unit = {
    val pairs = cases.map {
      case (v, n) =>
        edge(n)
        sh"$v, label %$n"
    }
    insts += sh"switch $scrut, label %$default [${r(pairs.map(i(_)))}${nl("]")}"
  }

  private implicit val showLocal: Show[Local] = Show {
    case Local(scope, id) => sh"$scope.$id"
  }
}

final case class LLBlock(name: Local,
                         params: Seq[(Local, Res)],
                         insts: Buf[Res],
                         isEntry: Boolean)