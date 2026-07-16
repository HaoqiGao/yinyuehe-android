package app.yinyuehe

import android.app.Instrumentation
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Assume.assumeTrue

object M3ADeviceProtocol {
  private val instrumentation: Instrumentation
    get() = InstrumentationRegistry.getInstrumentation()

  val context: Context
    get() = instrumentation.targetContext

  val arguments
    get() = InstrumentationRegistry.getArguments()

  fun requireHostDriven() {
    assumeTrue(
      "M3-A host handshake requires m3aHostDriven=true",
      arguments.getString("m3aHostDriven") == "true",
    )
  }

  fun requiredArgument(name: String): String =
    requireNotNull(arguments.getString(name)) { "Missing instrumentation argument: $name" }

  fun writeResult(marker: String, values: Map<String, Any?>) {
    val lines =
      buildList {
        add("marker=$marker")
        values.toSortedMap().forEach { (key, value) ->
          require(key.matches(KEY_PATTERN)) { "Invalid result key: $key" }
          val text = value.toString()
          require('\n' !in text && '\r' !in text) { "Result values must be single-line" }
          add("$key=$text")
        }
      }
    writeAtomic("$marker.result", lines.joinToString(separator = "\n", postfix = "\n"))
  }

  fun writeAtomic(name: String, value: String) {
    require(name.matches(FILE_PATTERN)) { "Invalid protocol file name: $name" }
    val directory = directory().apply(File::mkdirs)
    val target = File(directory, name)
    val temporary = File(directory, ".$name-${android.os.Process.myPid()}.tmp")
    FileOutputStream(temporary).use { output ->
      output.write(value.encodeToByteArray())
      output.fd.sync()
    }
    check(temporary.renameTo(target)) { "Unable to atomically publish $name" }
  }

  fun read(name: String): String = File(directory(), name).readText()

  fun result(name: String): Map<String, String> =
    read("$name.result")
      .lineSequence()
      .filter(String::isNotBlank)
      .associate { line ->
        val separator = line.indexOf('=')
        check(separator > 0) { "Malformed protocol line: $line" }
        line.substring(0, separator) to line.substring(separator + 1)
      }

  fun file(name: String): File = File(directory(), name)

  fun clearProtocolFiles() {
    directory().listFiles()?.forEach(File::delete)
  }

  private fun directory(): File = File(context.filesDir, DIRECTORY_NAME)

  private val KEY_PATTERN = Regex("[A-Za-z][A-Za-z0-9]*")
  private val FILE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
  private const val DIRECTORY_NAME = "m3a-device"
}
