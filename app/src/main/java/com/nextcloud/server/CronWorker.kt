package com.nextcloud.server

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File

class CronWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    override fun doWork(): Result {
        Log.d("CronWorker", "Starting Nextcloud background jobs")
        
        val filesDir = applicationContext.filesDir.absolutePath
        val phpBin = BinaryHelper.getPhpBinary(applicationContext)
        val cronPhp = File(filesDir, "nextcloud/cron.php").absolutePath
        
        return try {
            val pb = ProcessBuilder(phpBin, "-c", File(filesDir, "conf/php.ini").absolutePath, "-f", cronPhp)
            pb.directory(File(filesDir))
            val tmpDir = File(filesDir, "tmp").apply { mkdirs() }
            val env = pb.environment()
            val opensslCnf = File(filesDir, "conf/openssl.cnf")
            if (opensslCnf.exists()) {
                env["OPENSSL_CONF"] = opensslCnf.absolutePath
            }
            env["TMPDIR"] = tmpDir.absolutePath
            env["LD_LIBRARY_PATH"] = BinaryHelper.getLdLibraryPath(applicationContext) + ":" + (env["LD_LIBRARY_PATH"] ?: "")

            val process = pb.start()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Log.d("CronWorker", "Nextcloud background jobs completed successfully")
                Result.success()
            } else {
                Log.e("CronWorker", "Nextcloud background jobs failed with exit code $exitCode")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("CronWorker", "Error running Nextcloud background jobs", e)
            Result.failure()
        }
    }
}
