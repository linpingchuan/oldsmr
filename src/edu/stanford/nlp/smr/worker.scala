package edu.stanford.nlp.smr;
import scala.actors.Actor;
import scala.actors.Actor._;
import scala.actors.Exit;
import scala.collection.mutable.ArrayBuffer;
import scala.collection._;
import edu.stanford.nlp.smr.TransActor._;
import scala.actors.remote.RemoteActor._;
import scala.actors.remote.Node;

import Public._;
import Priv._;

class Worker extends Actor {
  import Worker._;

  def act() {
    trapExit = true;
    val actual_worker = realWorker(Actor.self);
    val accumulators = mutable.Map[JobID,Actor]();
    def getAcc(id : JobID) = accumulators.getOrElseUpdate(id,Worker.accumulator(id));
    loop {
      react {
        case Enliven(port,sym)=>
          alive(port);
          register(sym,Actor.self);
          reply{None}
        case Do(in,f,out) => 
         getAcc(in) ! Forward(getAcc(out));
         val outA = getAcc(out);
         getAcc(in) ! Retr(in,{
            x : (Int,Any) => 
              actual_worker ! { () => 
              outA ! Done(out,x._1,f(x._2))};
          });
        case Done(id,s,r)=> 
        getAcc(id) ! Done(id,s,r);
        case DoneAdding(id) => 
        //println("external " + id);
        getAcc(id) ! DoneAdding(id);
        case Retrieve(id,f,out,a) => 
        val a2 = SerializedActorToActor(a);
        getAcc(id) ! Retr(id,{
            x : (Int,Any) =>actual_worker ! { () => a2 ! Retrieved(out,x._1,f(x._2)); }; 
          });
        case Reserve(id,shard) => 
        getAcc(id) ! Add(shard);
        case Close=>
          actual_worker ! Exit(self,'close);
          accumulators.values.map(_ ! Exit(self,'close));
          exit();
        case Remove(id) => 
        val a = accumulators.get(id)
          accumulators -= id;
        a.map( _ ! Exit(self,'remove));
      }
    }
  }

  // private stuff:
  classLoader = this.getClass.getClassLoader;
}

object Worker {
  // intra worker communication:
  private case class Add(shard : Int); 
  private case class Forward(out : Actor); 
  private case class Enliven(port : Int,sym : Symbol);

  private case class Retr(id: JobID, f : ((Int,Any))=>Unit); 

  def apply()  : Worker = {
    val w = new Worker();
    w.start();
    w;
  }

  def apply(port : Int, sym : Symbol) : Worker = {
    val w = apply();
    w ! Enliven(port,sym);
    w
  }

  private def accumulator(id : JobID) = actor {
    val active = mutable.Set[Int]();
    val done = mutable.Map[Int,Any]();
    val awaiting = new ArrayBuffer[Actor]();
    var doneAdding = false;
    loop {
      react {
        case Exit(a,f) => exit();
        case Forward(out) =>
          //println("forward" + id + " to " + out);
          if(doneAdding) {
            active.foreach { x =>  out !? Add(x)}
            done.keys.foreach{ x => out !? Add(x)}
            out !? DoneAdding(1);
            //println("fast done" + id);
          } else {
            val a =  actor {
              //println("waiting on signal");
              react {
                case DoneAdding(_) => 
                  done.keys.foreach{ out ! Add(_)}
                  out ! DoneAdding(1);
                  //println("slow");
              } 
            }
            if(doneAdding && active.size == 0) a !  DoneAdding(0);
            else awaiting += a
          }
        case Retr(id,f) => 
          //println(Retr(id,f));
          val a =  actor {
            react {
              case DoneAdding(_) => 
              //println("Retr go!" + id);
              done.foreach(f)
            } 
          }
          if(doneAdding && active.size == 0) a !  DoneAdding(0);
          else awaiting += a
        case DoneAdding(_) => 
          //println("doneA" + id + "from" + Actor.sender);
          doneAdding = true;
          if(active.size == 0) {
            awaiting.foreach(_ ! DoneAdding(0));
          }
          reply{None}
        case Add(s) => 
          //println("adding" + s + " to " + id + "from " + Actor.sender);
          if(doneAdding) println("Warning: " + id + " got Add for shard " + s + "after doneAdding");
          if( !(done contains s)) active += s
          reply(None);
        case Done(x,s,r) => 
          //println("Done" + x + " " + s);
          active -= s; 
          done += (s->r);
          if(doneAdding && active.size == 0) {
            awaiting.foreach(_ ! DoneAdding(0));
          }
      }
    }
  }
  def realWorker(manager :Actor) = actor { 
      loop {
        react {
          case f : (()=>Any) => f();
          case Exit(_,_) => exit();
          case x => println("got something else" + x);
        }
      }
    }
}
