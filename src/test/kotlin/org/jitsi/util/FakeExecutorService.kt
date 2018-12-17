package org.jitsi.util

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.spy
import com.nhaarman.mockito_kotlin.whenever
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal abstract class FakeExecutorService : ExecutorService {
    private var jobs = JobsTimeline()
    val clock: FakeClock = spy()

    override fun execute(command: Runnable) {
        jobs.add(Job(command, clock.instant()))
    }

    override fun submit(task: Runnable): Future<*> {
        val future: CompletableFuture<Unit> = mock()
        val job = Job(task, clock.instant())
        whenever(future.cancel(any())).thenAnswer {
            job.cancelled = true
            true
        }
        jobs.add(job)
        return future
    }

    fun runOne() {
        if (jobs.isNotEmpty()) {
            val job = jobs.removeAt(0)
            if (!job.cancelled) {
                job.run()
            } else {
                // Check for another job since this one had been cancelled
                runOne()
            }
        }
    }

    fun runAll() {
        while (jobs.isNotEmpty()) {
            runOne()
        }
    }
}
