package org.jenkinsci.plugins.workflow.steps.durable_task;

import com.google.common.base.Function;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.XmlFile;
import hudson.model.Computer;
import hudson.model.PeriodicWork;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.durabletask.Controller;
import org.jenkinsci.plugins.durabletask.DurableTask;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.StepExecutionIterator;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Represents one task that is believed to still be running.
 *
 * @author Jesse Glick
 * @author Kohsuke Kawaguchi
 */
public class DurableTaskStepExecution extends StepExecution {
    private final DurableTask task;

    // these fields are set during the start
    private /*almost final*/ Controller controller;
    private /*almost final*/ String node;
    private /*almost final*/ String remote;

    private transient Object result;
    private transient Throwable error;

    public DurableTaskStepExecution(StepContext context, DurableTask task) {
        super(context);
        this.task = task;
    }

    @Override
    public boolean start() throws Exception {
        try {
            FilePath ws = context.get(FilePath.class);
            assert ws != null : context.getClass() + " failed to provide a FilePath even though one was requested";
            String remote = ws.getRemote();
            String node = null;
            for (Computer c : Jenkins.getInstance().getComputers()) {
                if (c.getChannel() == ws.getChannel()) {
                    node = c.getName();
                    break;
                }
            }
            if (node == null) {
                throw new IllegalStateException("no known node for " + ws);
            }
            controller = task.launch(context.get(EnvVars.class), ws, context.get(Launcher.class), context.get(TaskListener.class));
            this.node = node;
            this.remote = remote;
        } catch (Exception x) {
            context.onFailure(x);
        }
        return false;
        // TODO implement stop, however it is design (will need to call Controller.stop)
    }

    private @CheckForNull FilePath getWorkspace() throws IOException, InterruptedException {
        Computer c = Jenkins.getInstance().getComputer(node);
        if (c == null) {
            LOGGER.log(Level.FINE, "no such computer {0}", node);
            return null;
        }
        if (c.isOffline()) {
            LOGGER.log(Level.FINE, "{0} is offline", node);
            return null;
        }
        FilePath ws = new FilePath(c.getChannel(), remote);
        if (!ws.isDirectory()) {
            error = new AbortException("missing workspace " + remote + " on " + node);
            return null;
        }
        return ws;
    }

    @Override
    public void stop() throws Exception {
        FilePath ws = getWorkspace();
        if (ws!=null)
            controller.stop(ws);
    }

    private static void checkAll() throws ExecutionException, InterruptedException {
        StepExecution.applyAll(DurableTaskStepExecution.class,new Function<DurableTaskStepExecution, Void>() {
            @Override
            public Void apply(DurableTaskStepExecution rt) {
                if (rt.check()==CheckResult.DONE) {
                    rt.report();
                }
                return null;
            }
        }).get();
    }

    private enum CheckResult {
        /** Task still believed to be running, but has produced no new output since the last check. */
        NO_CHANGE,
        /** Task has produced new output but is still running. */
        UPDATED,
        /** Task is finished (or in an unrecoverable error state). */
        DONE
    }

    /** Checks for progress or completion of the external task. */
    CheckResult check() {
        try {
            FilePath ws = getWorkspace();
            if (ws==null) {
                return CheckResult.DONE;
            }
            TaskListener listener = context.get(TaskListener.class);
            boolean wrote = controller.writeLog(ws, listener.getLogger());
            Integer exitCode = controller.exitStatus(ws);
            if (exitCode == null) {
                LOGGER.log(Level.FINE, "still running in {0} on {1}", new Object[] {remote, node});
                return wrote ? CheckResult.UPDATED : CheckResult.NO_CHANGE;
            } else if (exitCode == 0) {
                result = exitCode; // TODO could add an option to have this be text output from command
            } else {
                error = new AbortException("script returned exit code " + exitCode);
            }
            controller.cleanup(ws);
            return CheckResult.DONE;
        } catch (IOException x) {
            error = x;
        } catch (InterruptedException x) {
            error = x;
        }
        return CheckResult.DONE;
    }

    /** Reports success or failure of step, outside synchronization block to avoid deadlocks. */
    void report() {
        if (error != null) {
            context.onFailure(error);
        } else {
            context.onSuccess(result);
        }
    }

    @Restricted(NoExternalUse.class)
    @Extension
    public static final class Checker extends PeriodicWork {

        @Override public long getRecurrencePeriod() {
            return 5000; // 5s
        }

        @Override protected void doRun() throws Exception {
            checkAll();
        }

    }

    private static final Logger LOGGER = Logger.getLogger(DurableTaskStepExecution.class.getName());
}
