package org.scalaconsole
package fxui
import java.io._
import javafx.concurrent.Task
import org.scalaconsole.data.{CommandLineOptions, ClassLoaderManager}
import java.util.jar.JarFile
import scala.tools.nsc.plugins.PluginDescription
import org.scalaconsole.DetachedILoop
import scala.tools.nsc.Settings
import java.util.concurrent.ArrayBlockingQueue
import javafx.application.Platform
import scalaz.Alpha.T

class CoreDelegate(val controller: ScalaConsoleController) {
  val commandQueue = new ArrayBlockingQueue[(Symbol, String)](10)

  // 这一对stream是从repl的输出到右侧的textarea的数据通道，不变
  val outputIs = new PipedInputStream(4096)
  val replOs   = new PipedOutputStream(outputIs)

  val sysOutErr = new PrintStream(replOs) {
    override def write(buf: Array[Byte], off: Int, len: Int) {
      val str = new String(buf, off, len)
      replOs.write(str.getBytes)
      replOs.flush()
    }
  }
  System.setOut(sysOutErr)

  def startOutputRenderer() = {
    val task = new Task[Unit] {
      override def call() = {
        for (line <- io.Source.fromInputStream(outputIs).getLines) {
          Platform.runLater(new Runnable {
            override def run() = {
              controller.outputArea.appendText(s"$line\n")
            }
          })
        }
      }
    }
    registerTask(task)
    val thread = new Thread(task)
    thread.setDaemon(true)
    thread.start()
  }

  /**
   * 启动一个新的repl，用于切换scala版本和reset时。每次调用它，就生成一对stream和一个Task来运行scala ILoop
   * @return
   */
  def connectToRepl(writer: PrintWriter, pasteFunc: String => Unit) = {
    val task = new Task[Unit] {
      override def call() = {
        var quitCommand = false
        while (!isCancelled) {
          commandQueue.take() match {
            case ('Normal, script: String) =>
              writer.write(script)
              if (!script.endsWith("\n")) writer.write("\n")
              writer.flush()
              if (script == ":q")
                cancel()
            case ('Paste, script: String) =>
              println("// Interpreting in paste mode ")
              pasteFunc(script)
              println("// Exiting paste mode. ")
          }
        }
      }
    }
    registerTask(task)
    val thread = new Thread(task)
    thread.setDaemon(true)
    thread.start()
  }

  def isToReader(is: InputStream) = new BufferedReader(new InputStreamReader(is))

  def osToWriter(os: OutputStream) = new PrintWriter(new OutputStreamWriter(os))

  def startRepl() = {
    def task = new Task[Unit]() {

      override def call() = {
        val replIs = new PipedInputStream(4096)
        val scriptWriter = new PrintWriter(new OutputStreamWriter(new PipedOutputStream(replIs)))
        val settings = new Settings
        CommandLineOptions.value.map(settings.processArgumentString)

        for (path <- data.DependencyManager.boundedExtraClasspath(ClassLoaderManager.currentScalaVersion)) {
          settings.classpath.append(path)
          settings.classpath.value = settings.classpath.value // set settings.classpath.isDefault to false
          // enable plugins
          if (path.endsWith(".jar")) {
            val jar = new JarFile(path)
            for (xml <- Option(jar.getEntry(Constants.PluginXML))) {
              val in = jar getInputStream xml
              val plugin = PluginDescription.fromXML(in)
              if (settings.pluginOptions.value.exists(_ == plugin.name + ":enable")) {
                settings.plugin.appendToValue(path)
              }
              in.close()
            }
          }
        }

        if (ClassLoaderManager.isOrigin) {
          settings.usejavacp.value = true
          val iloop = new DetachedILoop(isToReader(replIs), osToWriter(replOs))
          connectToRepl(scriptWriter, s => iloop.intp.interpret(s))
          iloop.process(settings)
        } else {
          val (cl, scalaBootPath) = ClassLoaderManager.forVersion(ClassLoaderManager.currentScalaVersion)
          settings.usejavacp.value = true
          settings.bootclasspath.value = scalaBootPath
          cl.asContext {
            settings.embeddedDefaults(cl)
            val remoteClazz = Class.forName("org.scalaconsole.DetachedILoop", false, cl)
            val _iloop = remoteClazz.getConstructor(classOf[java.io.BufferedReader], classOf[java.io.PrintWriter]).
                         newInstance(isToReader(replIs), osToWriter(replOs))
            connectToRepl(scriptWriter, { s =>
              val _intp = remoteClazz.getMethod("intp").invoke(_iloop)
              val remoteIMain = Class.forName("scala.tools.nsc.interpreter.IMain", false, cl)
              remoteIMain.getMethod("interpret", classOf[String]).invoke(_intp, s)
            })
            remoteClazz.getMethod("process", classOf[Array[String]]).invoke(_iloop, settings.recreateArgs.toArray)
          }
        }

        replIs.close()
        scriptWriter.close()
      }
    }
    val thread = new Thread(task)
    thread.setDaemon(true)
    thread.start()
  }

  def run(script: String) = {
    commandQueue.put('Normal, script)
  }

  def runPaste(script: String) = {
    commandQueue.put('Paste, script)
  }

  var tasks = List[Task[_]]()

  def registerTask[T](task: Task[T]) = tasks ::= task
  def cancelTasks() = for(task <- tasks) task.cancel()

  startOutputRenderer()
  startRepl()
  controller.scriptArea.setFont(Variables.displayFont)
  controller.outputArea.setFont(Variables.displayFont)
}
