/*
 * Copyright 2024 Lukas Schröder, Samuel Skovbakke & Alexander Sommer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix
import ca.uwaterloo.flix.MutationGenerator.{MutatedDef, mutateRoot}
import ca.uwaterloo.flix.MutationDataHandler
import ca.uwaterloo.flix.runtime.TestFn
import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Ast.Annotation.Benchmark
import ca.uwaterloo.flix.language.ast.Ast.Constant
import ca.uwaterloo.flix.language.ast.{Symbol, Type, TypedAst}
import ca.uwaterloo.flix.language.ast.TypedAst.{Expr, MutationType, Root, empty}
import dev.flix.runtime.Global

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import ca.uwaterloo.flix.language.phase.jvm.BackendObjType

import java.io.{File, FileWriter}
import scala.collection.immutable.HashMap
import scala.util.Random
import ca.uwaterloo.flix.language.ast.SourceLocation
import scala.collection.immutable
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.CstMut
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.RecordSelectMut
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.VarMut
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.ListMut
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.CompMut
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.CaseSwitch
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.CaseDeletion
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.IfMut
import ca.uwaterloo.flix.language.ast.TypedAst.MutationType.SigMut

///
/// A Mutation Tester can be used to evaluate ones test suite.
/// It is based on the following:
///     - given a source and test module we can test the source code.
///     - A competent programmer writes almost correct code and only
///       needs to make few changes to achieve the correct program.
///     - We can simulate the competent programmer mistakes by making single changes to the source code,
///       which is called a mutation.
///     - Running the mutants with the given tests, we can estimate the quality of the test suite.
/// Mutants are created by greedily going through the AST and making all sensible changes.
///

object MutationTester {

    /**
      * When the test suite is ran, a mutant can either be killed or survive.
      *
      * A special case is added when the test doesn't terminate.
    */
    sealed trait TestRes

    object TestRes {
        case object MutantKilled extends TestRes

        case object MutantSurvived extends TestRes

        case object Unknown extends TestRes

        case object Equivalent extends TestRes
    }
    var nonKilledStrList: List[String] = List.empty

    /**
      * Compiles until after the typing stage, creates mutants and then runs them.
      *
      * It also keeps track of the time it took to generate all the mutations
      */
  def run(flix: Flix, testModule: String, productionModule: String, percentage: Int): Unit = {
    println(s"mutating module: $productionModule")
    val root = flix.check().unsafeGet
    val start = System.nanoTime()
    // println(root.sigs.filter(t => t._1.toString.equals("Add.add")))
    val mutations = MutationGenerator.mutateRoot(root, productionModule)
    val mutationsAreNotMade = mutations.isEmpty
    if (mutationsAreNotMade) { // fast exit if no mutations were made
      println("No mutations were made. Please verify that you have supplied the correct module names.")
      return
    }
    //val selectedMutants = randomSelection(percentage, mutations)
    val end = System.nanoTime() - start
    val timeSec = end.toFloat / 1_000_000_000.0
    println(s"time to generate mutations: $timeSec")
    val lastRoot = insertDecAndCheckIntoRoot(root)
    val (results, ttb, bbs, bugs, time) = runMutations(flix, testModule, lastRoot, sortMutants(mutations))
    MutationDataHandler.processData(results, time, productionModule)
    MutationDataHandler.writeTTBToFile(s"TTB: $productionModule: ${ttb}")
    MutationDataHandler.writeBBSToFile(s"BBS: $productionModule: ${bbs}: $bugs")
    writeReportsToFile(nonKilledStrList)
  }

    private def writeReportsToFile(reportsList: List[String]): Unit = {
      if (reportsList.isEmpty) return
      val stars = "*".repeat(60)
      var strForFile = reportsList.foldLeft("")((acc, str) => s"$acc\n$stars\n\n$str")
      strForFile = strForFile.substring(2)
      val fileWriter = new FileWriter(new File(s"mutation_report.txt"))
      fileWriter.write(strForFile)
      fileWriter.close()
    }

    private def randomSelection(percentage: Int, mutants: List[(Symbol.DefnSym, List[MutatedDef])]): List[(Symbol.DefnSym, List[MutatedDef])] =  {
      mutants.map{
        case (sym, mDefs) =>
          val shuffled = Random.shuffle(mDefs)
          val toRemain = (mDefs.length * percentage) / 100
          if (toRemain > 0)
            (sym, shuffled.take(toRemain))
          else (sym, shuffled.take(1))
      }
    }


    private def sortMutants(mutants: List[MutatedDef]): List[MutatedDef] = {
      val csts = mutants.filter(m => m.mutType match {
        case CstMut(_) => true
        case _ =>  false
      })
      val recordSelectMut = mutants.filter(m => m.mutType match {
        case RecordSelectMut(_) => true
        case _ =>  false
      })
      val ifMut = mutants.filter(m => m.mutType match {
        case IfMut(_) => true
        case _ =>  false
      })
      val compMut = mutants.filter(m => m.mutType match {
        case CompMut(_) => true
        case _ =>  false})
      val caseDeletion = mutants.filter(m => m.mutType match {
        case CaseDeletion(_) => true
        case _ =>  false})
      val sigMut = mutants.filter(m => m.mutType match {
        case SigMut(_) => true
        case _ =>  false})
      val caseSwitch = mutants.filter(m => m.mutType match {
        case CaseSwitch(_, _) => true
        case _ =>  false})
      val varMut = mutants.filter(m => m.mutType match {
        case VarMut(_, _) => true
        case _ =>  false})
      val listMut = mutants.filter(m => m.mutType match {
        case ListMut() => true
        case _ =>  false})
      caseSwitch ::: listMut ::: ifMut ::: varMut ::: csts ::: sigMut ::: caseDeletion ::: compMut ::: recordSelectMut
    }

  /**
      * Inserts a masked call to the static function decAndCheck into all defs in the root
      *
      * @param root: The TAST before the insertion of decAndCheck
      * @return The root with the call inserted into all defs
      */
    private def insertDecAndCheckIntoRoot(root: Root): Root = {
        val newDefs = root.defs.map({
            case (sym, fun) =>
                sym -> insertDecAndCheckInDef(fun)
        })
        root.copy(defs = newDefs)
    }

  /**
    * Returns a human readable string representing a mutation given a surviving mutant
    * @param mutType The MutationType of a surviving mutant
    * @return a string that accurately reports what mutation was used
    */
    private def printMutation(mutType: TypedAst.MutationType): String = {
      mutType match {
        case MutationType.CstMut(cst) => cst match {
          case Constant.Unit => "Unit"
          case Constant.Null => "Null"
          case Constant.Bool(lit) => s"Changed to ${lit.toString}"
          case Constant.Char(lit) => s"Changed to ${lit.toString}"
          case Constant.Float32(lit) => s"Changed to ${lit.toString}"
          case Constant.Float64(lit) => s"Changed to ${lit.toString}"
          case Constant.BigDecimal(lit) => s"Changed to ${lit.toString}"
          case Constant.Int8(lit) => s"Changed to ${lit.toString}"
          case Constant.Int16(lit) => s"Changed to ${lit.toString}"
          case Constant.Int32(lit) => s"Changed to ${lit.toString}"
          case Constant.Int64(lit) => s"Changed to ${lit.toString}"
          case Constant.BigInt(lit) => s"Changed to ${lit.toString}"
          case Constant.Str(lit) => s"Changed to \"${lit.toString}\""
          case Constant.Regex(lit) => s"Changed to ${lit.toString}"
        }
        case MutationType.SigMut(sig) =>
          val op = sig.name match {
          case "sub" => "-"
          case "mul" =>"*"
          case "div" =>"/"
          case "add" =>"+"
          case e => e
        }
          s"Changed to ${op}"
        case MutationType.IfMut(bool) => s"Changed the if-then-else condition to ${bool.toString}"
        case MutationType.CompMut(comp) => s"Changed to ${comp.name}"
        case MutationType.CaseSwitch(x, y) => s"Switched case ${x + 1} and case ${y + 1}"
        case MutationType.VarMut(apply, varName) =>  apply.sym.name match {
            case "sub" => s"Changed variable to ${varName.text} - 1"
            case "add" =>s"Changed variable to ${varName.text} + 1"
            case e => e
          }
        case MutationType.CaseDeletion(i) => s"Deleted case number ${i + 1}"
        case MutationType.RecordSelectMut(name) => s"Selects ${name.name}"
        case MutationType.ListMut() => "Changed to the empty list"
      }
    }

  /**
    * Insert a masked call to a static function which is used for terminating infinite loops
    * @param d TypedAst.Def
    * @return A TypedAst.Def where the call has been inserted
    */
    def insertDecAndCheckInDef(d: TypedAst.Def): TypedAst.Def = {
      val loc = d.exp.loc
      val method = classOf[Global].getMethods.find(m => m.getName.equals("decAndCheck")).get
      val InvokeMethod = Expr.InvokeStaticMethod(method, Nil, Type.Int64, Type.IO, loc)
      val mask = Expr.UncheckedMaskingCast(InvokeMethod, Type.Int64, Type.Pure, loc)
      val statement = Expr.Stm(mask, d.exp, d.exp.tpe, d.exp.eff, d.exp.loc)
      d.copy(exp = statement)
    }

  /**
    * Prints a message if enough time has passed and updates the the temp time
    * which will later be passed to this function again
    * @param message A string that will be printed to terminal
    * @param timePassed time passed since last update
    * @return temp time; updated if message was printed
    */
    private def progressUpdate(message: String, timePassed: Long): Long = {
        var temp = timePassed
        val now = System.nanoTime()
        val nanoMin = 60_000_000_000.0
        // print update if a minute has passed since last print
        if (now - temp > nanoMin) {
            println(message)
            temp = now
        }
        temp
    }

    private case class TestKit(flix: Flix, root: Root, testModule: String)
    private case class ReportAcc(totalSurvivorCount: Int, totalUnknowns: Int, equivalent: Int)


  /**
    * Runs the generated mutants against a test module
    * @param flix the Flix object
    * @param testModule the name of the test module
    * @param root TypedAst.Root
    * @param mutatedDefs list of mutants
    * @return the results of running the generated mutants along with time to bug
    */
    private def runMutations(flix: Flix, testModule: String, root: TypedAst.Root, mutatedDefs: List[MutatedDef]): (List[(MutationType, TestRes)], Double, Double, Int, Double) = {
        val totalStartTime = System.nanoTime()
        val temp = totalStartTime
        val amountOfMutants = mutatedDefs.length
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd: HH:mm")
        val emptyList: List[(MutatedDef, TestRes)] = Nil

        val emptySkip: SkipM = SkipM.Default(Nil)

        val newRep = ReportAcc(0,0,0)
        val localAcc: (ReportAcc, Double, Long, Int, List[(MutatedDef, TestRes)], Long, SkipM) = (newRep, totalStartTime.toDouble, temp, 0, emptyList, 0.toLong, emptySkip)
        val (rep, _, _, mutantsRun, mOperatorResults, timeToBug, survived) = mutatedDefs.foldLeft(localAcc)((acc, mut) => {
            val kit = TestKit(flix, root, testModule)

              testMutantsAndUpdateProgress(acc, mut, kit, f)
        })

      val totalTime = reportResults(totalStartTime, mutantsRun, rep.totalSurvivorCount, rep.totalUnknowns, rep.equivalent)
      println((timeToBug - totalStartTime).toDouble / 1_000_000_000.toDouble)

      val bugs = survived.bugs
      val mOpRes = mOperatorResults.map{
        case (mDef, tR) => (mDef.mutType, tR)
      }

      (mOpRes, (timeToBug - totalStartTime).toDouble / 1_000_000_000.toDouble, bugs.toDouble / totalTime, bugs, totalTime)
    }



  /**
    * Prints a string of the results to terminal
    * @param totalStartTime
    * @param amountOfMutants
    * @param totalSurvivorCount
    * @param totalUnknowns
    * @param equivalents
    * @return totalEndTime
    */
  private def reportResults(totalStartTime: Long, amountOfMutants: Int, totalSurvivorCount: Int, totalUnknowns: Int, equivalents: Int): Double = {
    val totalEndTime = System.nanoTime() - totalStartTime
    println(s"mutation score: ${(amountOfMutants - totalSurvivorCount - totalUnknowns -equivalents).toFloat/(amountOfMutants - totalUnknowns - equivalents).toFloat}")
    println(s"There where $totalSurvivorCount surviving mutations, out of $amountOfMutants mutations")
    println(s"There where $equivalents equivalent mutations, out of $amountOfMutants mutations")
    println(s"There where $totalUnknowns mutations that did not finish terminating, out of $amountOfMutants mutations")
    val nano = 1_000_000_000.0
    val ifMutants = amountOfMutants > 0
    val average = if (ifMutants) (totalEndTime / nano) / amountOfMutants else 0
    println(s"Average time to test a mutant:  $average seconds")
    val time = if (ifMutants) totalEndTime.toFloat / nano else 0
    println(s"Total time to test all mutants: $time seconds")

    totalEndTime.toDouble / nano
  }




  sealed trait SkipM {
    def shouldSkip(mut: MutatedDef): Boolean = SkipM.contains(this, mut)
    def ::(mut: MutatedDef): SkipM = SkipM.skipPrepend(this, mut)
    def bugs: Int = SkipM.bugs(this)
    def length: Int = SkipM.length(this)
  }


  // Object for omptimizations
  object SkipM {

    // The default
    case class Default(survivors: List[MutatedDef]) extends SkipM
    // The different omptimizations
    case class LocOp(locs: List[SourceLocation]) extends SkipM
    case class LocRecOp(locsRecs: List[(SourceLocation, MutationType)]) extends SkipM

    private  def length(skip: SkipM): Int = {
      skip match {
        case Default(survivors) => survivors.length
        case LocOp(locs) => locs.length
        case LocRecOp(locsRecs) => locsRecs.length
      }
    }

    private def contains(skip: SkipM, mut: MutatedDef): Boolean = {
      skip match {
        case SkipM.Default(_) => false
        case SkipM.LocOp(locs) => locs.contains(mut.df.exp.loc)

        case SkipM.LocRecOp(locRecs) =>
          val (locs, mutType) = locRecs.unzip
          val con = locs.contains(mut.df.exp.loc)
          val sameMut = locRecs.unzip._2.exists(mT => equalMutOp(mT, mut.mutType))
          con && sameMut
      }
    }
    private def skipPrepend(skip: SkipM, mut: MutatedDef): SkipM = {
      skip match {
        case SkipM.Default(survivors) => SkipM.Default(mut :: survivors)
        case SkipM.LocOp(locs) => SkipM.LocOp(mut.df.exp.loc :: locs)
        case SkipM.LocRecOp(locRecs) => SkipM.LocRecOp((mut.df.exp.loc, mut.mutType):: locRecs)
      }
    }
    private def equalMutOp(m1: MutationType, m2: MutationType): Boolean = {
      (m1, m2) match {
        case (CaseDeletion(_), CaseDeletion(_)) => true
        case (CaseSwitch(_,_), CaseSwitch(_,_)) => true
        case (IfMut(_), IfMut(_)) => true
        case (SigMut(_), SigMut(_)) => true
        case (CstMut(_), CstMut(_)) => true
        case (CompMut(_), CompMut(_)) => true
        case (VarMut(_,_), VarMut(_,_)) => true
        case (RecordSelectMut(_), RecordSelectMut(_)) => true
        case (ListMut(), ListMut()) => true
        case (_, _) => false
      }
    }
    private def bugs(skip: SkipM): Int = {
      skip match {
        case Default(survivors) =>
          val emptyList: List[SourceLocation] = Nil
          survivors.foldLeft(emptyList)((acc, m) => if (acc.contains(m.df.exp.loc)) acc else  m.df.exp.loc :: acc).length
        case LocOp(locs) => locs.length
        case LocRecOp(locsRecs) => locsRecs.length
      }
    }
  }


  /**
    * runs all mutants for a given def against the test module while accumulating and also calls progress update
    * @param acc accumulator
    * @param mut tuple of a function definition along with the list mutants with mutations in that function definition
    * @param testKit
    * @param f DateTimeFormatter
    * @return acc
    */
    private def testMutantsAndUpdateProgress(acc: (ReportAcc, Double, Long, Int, List[(MutatedDef, TestRes)], Long, SkipM), mut: MutatedDef, testKit: TestKit, f: DateTimeFormatter) = {
        val (rep, time, accTemp, mAmount, mTypeResults, timeToBug, survived) = acc
        val start = System.nanoTime()

        if (survived.shouldSkip(mut)) {
          acc
        } else {
          val mutationAmount = mAmount + 1

          val testResult = compileAndTestMutant(mut.df, mut.df.sym, testKit)

          val nano = 1_000_000_000
          val newTime = time + (System.nanoTime() - start).toDouble / nano
          val (newSurvivorCount, newTTB, newSkip): (Int, Long, SkipM) = {
            if (testResult.equals(TestRes.MutantSurvived)) {

              (rep.totalSurvivorCount + 1, if (timeToBug == 0) System.nanoTime else timeToBug, mut :: survived)
            }
            else (rep.totalSurvivorCount, timeToBug, survived)
          }

          val newUnknownCount = if (testResult.equals(TestRes.Unknown))  rep.totalUnknowns + 1 else rep.totalUnknowns
          val newEQCount = if (testResult.equals(TestRes.Equivalent))  rep.equivalent + 1 else rep.equivalent
          if (testResult.equals(TestRes.MutantSurvived)) {
            println(testKit.flix.getFormatter.code(mut.df.exp.loc, printMutation(mut.mutType)))
            nonKilledStrList = testKit.flix.getFormatter.code(mut.df.exp.loc, printMutation(mut.mutType)) :: nonKilledStrList
          }

          val res_rep = ReportAcc(newSurvivorCount, newUnknownCount, newEQCount)
          val now = LocalDateTime.now()
          val message = s"[${f.format(now)}] Mutants: $mutationAmount, Killed: ${mutationAmount - rep.totalSurvivorCount - rep.totalUnknowns - rep.equivalent}, Survived: ${rep.totalSurvivorCount}, Unknown: ${rep.totalUnknowns}"
          val newTemp = progressUpdate(message, accTemp)
          (res_rep, newTime, newTemp, mutationAmount, (mut, testResult) :: mTypeResults, newTTB, newSkip)
        }
    }

    /**
    */

  /**
    * Inserts a given mutant into the root then compiles the root, afterwards tests not in the test module are filtered
    * then the compiled code is run with the tests
    * @param df the mutant
    * @param mut the name of the function definition where the mutation is
    * @param testKit
    * @return the results of running the tests
    */
    private def compileAndTestMutant(df: TypedAst.Def, mut: Symbol.DefnSym, testKit: TestKit): TestRes = {
        val defs = testKit.root.defs
        val n = defs + (mut -> df)
        val newRoot = testKit.root.copy(defs = n)
        val cRes = testKit.flix.codeGen(newRoot).unsafeGet
        val testsFromTester = cRes.getTests.filter { case (s, _) => s.namespace.head.equals(testKit.testModule) }.toList
        runTest(testsFromTester)
    }


    /**
      * Runs all tests on the individual mutation, until a test failed or all succeeded.
      *
      * @param testsFromTester  : all tests regarding the given source code.
      * @return TestRes.MutantKilled: A Test failed and we mark the mutant as killed.
      * @return TestRes.MutantSurvived: All Test succeeded and we can mark the mutant as survived.
      * @return TestRes.Unknown: The test didn't terminate within a fixed number of iterations. The mutant is marked as
      *         unknown and isn't included in the mutation score.
      * @return TestRes.Equivalent: A Test was made that equivalent only at runtime
      *
      */
    private def runTest(testsFromTester: List[(Symbol.DefnSym, TestFn)]): TestRes = {
        testsFromTester match {
            case x :: xs =>
                try {
                    x._2.run() match {
                        case java.lang.Boolean.TRUE => runTest(xs)
                        case java.lang.Boolean.FALSE => TestRes.MutantKilled
                        case _ => runTest(xs)
                    }
                } catch {
                    case e: Throwable => {
                      e.getClass.toString match {
                        case "class dev.flix.runtime.MutationError$" => TestRes.Unknown
                        case "class dev.flix.runtime.EQMutantException$" => TestRes.Equivalent
                        case _ => TestRes.MutantKilled
                      }
                    }
                }
            case _ => TestRes.MutantSurvived
        }
    }
}
