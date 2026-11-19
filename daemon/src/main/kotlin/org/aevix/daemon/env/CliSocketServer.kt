package org.aevix.daemon.env

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.launch
import org.aevix.daemon.*
import org.aevix.daemon.data.FileSystem
import org.aevix.daemon.ipc.CliHandler

private const val TAG = "AevixCliSever"

object CliSocketServer {

  private var isRunning = false

  fun start() {
    if (isRunning) return
    isRunning = true

    val serverThread = Thread {
      // Keep these references outside the loop to prevent GC from closing them
      var rootSocket: LocalSocket? = null
      var server: LocalServerSocket? = null
      var socketFile: File? = null

      try {
        val cliSocketPath: String = FileSystem.setupCli()
        socketFile = File(cliSocketPath)

        // Create a standard LocalSocket
        rootSocket = LocalSocket()
        // Bind it to the filesystem path
        val address = LocalSocketAddress(cliSocketPath, LocalSocketAddress.Namespace.FILESYSTEM)
        rootSocket.bind(address)

        // Owner only, explicitly. `bind` creates the node with the process umask applied, and the
        // daemon runs as root where that is often 0 - a socket any app could connect to and drive
        // the CLI's commands through. The token in the handshake is a compile-time UUID, which is
        // not a secret: it sits in the daemon's BuildConfig and in the CLI binary, both readable.
        // The file mode is the boundary that actually holds.
        runCatching { Os.chmod(cliSocketPath, "600".toInt(8)) }
            .onFailure { Log.w(TAG, "Could not restrict the CLI socket to its owner", it) }

        // LocalServerSocket(FileDescriptor) requires the FD to already be listening.
        Os.listen(rootSocket.fileDescriptor, 50)
        // Wrap the underlying FileDescriptor into a ServerSocket
        server = LocalServerSocket(rootSocket.fileDescriptor)

        Log.d(TAG, "CLI server started at $cliSocketPath")

        while (!Thread.currentThread().isInterrupted) {
          try {
            val clientSocket = server.accept()
            AevixDaemon.scope.launch { handleClient(clientSocket) }
          } catch (e: IOException) {
            if (Thread.currentThread().isInterrupted) break
            Log.w(TAG, "Error accepting client", e)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Fatal CLI Server error", e)
      } finally {
        try {
          server?.close()
          rootSocket?.close()
        } catch (ignored: Exception) {}

        if (socketFile?.exists() == true) {
          socketFile.delete()
        }
        isRunning = false
        Log.d(TAG, "CLI server stopped")
      }
    }

    serverThread.name = "AevixCliListener"
    serverThread.priority = Thread.MIN_PRIORITY
    serverThread.start()
  }

  private fun handleClient(socket: LocalSocket) {
    try {
      val input = DataInputStream(socket.inputStream)
      val output = DataOutputStream(socket.outputStream)

      // Read & Verify Security Token (UUID MSB/LSB)
      val msb = input.readLong()
      val lsb = input.readLong()
      if (msb != BuildConfig.CLI_TOKEN_MSB || lsb != BuildConfig.CLI_TOKEN_LSB) {
        socket.close()
        return
      }

      val requestJson = input.readUTF()
      val request = AevixIPC.gson.fromJson(requestJson, CliRequest::class.java)

      // Intercept Log Streaming specifically before CliHandler
      if (request.command == "log" && request.action == "stream") {
        val verbose = request.options["verbose"] as? Boolean ?: false
        val logFile = if (verbose) LogcatMonitor.getVerboseLog() else LogcatMonitor.getModulesLog()

        if (logFile != null && logFile.exists()) {
          val response = CliResponse(success = true, isFdAttached = true)
          output.writeUTF(AevixIPC.gson.toJson(response))

          // The descriptor is duplicated into the socket by the kernel when the ancillary data is
          // written, so this side can drop its own reference as soon as `write` returns. It used
          // to be left to the finalizer, and `socket.close()` does not close it - that is an
          // unrelated descriptor - so every streamed log leaked one.
          FileInputStream(logFile).use { fis ->
            socket.setFileDescriptorsForSend(arrayOf(fis.fd))
            output.write(1) // Trigger byte to "carry" the ancillary FD data
          }
          return
        } else {
          output.writeUTF(
              AevixIPC.gson.toJson(CliResponse(success = false, error = "Log file not found.")))
          return
        }
      }

      // Standard commands go to CliHandler as usual
      val response = CliHandler.execute(request)
      output.writeUTF(AevixIPC.gson.toJson(response))
    } finally {
      socket.close()
    }
  }
}
