package org.jenkinsci.plugins.workflow.cps;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Result;
import hudson.util.CopyOnWriteList;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.TestDurabilityHintProvider;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionList;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assert;
import org.junit.Rule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class AbstractFlowDurabilityTest {

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();


    protected WorkflowRun createAndRunSleeperJob(Jenkins jenkins, String jobName, FlowDurabilityHint durabilityHint, boolean resumeBlocked) throws Exception {
        Item prev = jenkins.getItemByFullName(jobName);
        if (prev != null) {
            prev.delete();
        }

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, jobName);
        job.setResumeBlocked(resumeBlocked);
        CpsFlowDefinition def = new CpsFlowDefinition("node {\n " +
                "sleep 30 \n" +
                "} \n" +
                "echo 'I like cheese'\n", false);
        TestDurabilityHintProvider provider = Jenkins.get().getExtensionList(TestDurabilityHintProvider.class).get(0);
        provider.registerHint(jobName, durabilityHint);
        job.setDefinition(def);
        WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
        story.j.waitForMessage("Sleeping for", run);
        Assert.assertFalse(run.getExecution().isComplete());
        Assert.assertFalse(((CpsFlowExecution)(run.getExecution())).done);
        Assert.assertEquals(durabilityHint, run.getExecution().getDurabilityHint());
        Assert.assertEquals("sleep", run.getExecution().getCurrentHeads().get(0).getDisplayFunctionName());
        return run;
    }


    static void verifyFailedCleanly(Jenkins j, WorkflowRun run) throws Exception {

        if (run.isBuilding()) {  // Give the run a little bit of time to see if it can resume or not
            FlowExecution exec = run.getExecution();
            if (exec instanceof CpsFlowExecution) {
                waitForBuildToResumeOrFail(run);
            } else {
                Thread.sleep(4000L);
            }
        }

        if (run.getExecution() instanceof CpsFlowExecution) {
            CpsFlowExecution cfe = (CpsFlowExecution)(run.getExecution());
            assert cfe.isComplete() || (cfe.programPromise != null && cfe.programPromise.isDone());
        }

        assert !run.isBuilding();

        if (run.getExecution() instanceof  CpsFlowExecution) {
            Assert.assertEquals(Result.FAILURE, ((CpsFlowExecution) run.getExecution()).getResult());
        }

        Assert.assertEquals(Result.FAILURE, run.getResult());
        assert !run.isBuilding();
        // TODO verify all blocks cleanly closed out, so Block start and end nodes have same counts and FlowEndNode is last node
        verifyCompletedCleanly(j, run);
    }

    /** Waits until the build to resume or die. */
    static void waitForBuildToResumeOrFail(WorkflowRun run) throws Exception {
        CpsFlowExecution execution = (CpsFlowExecution)(run.getExecution());
        long nanoStartTime = System.nanoTime();
        while (true) {
            if (!run.isBuilding()) {
                return;
            }
            long currentTime = System.nanoTime();
            if (TimeUnit.SECONDS.convert(currentTime-nanoStartTime, TimeUnit.NANOSECONDS) > 10) {
                StringBuilder builder = new StringBuilder();
                builder.append("Run result: "+run.getResult());
                builder.append(" and execution != null:"+run.getExecution() != null+" ");
                FlowExecution exec = run.getExecution();
                if (exec instanceof CpsFlowExecution) {
                    CpsFlowExecution cpsFlow = (CpsFlowExecution)exec;
                    builder.append(", FlowExecution is paused: "+cpsFlow.isPaused())
                            .append(", FlowExecution is complete: "+cpsFlow.isComplete())
                            .append(", FlowExecution result: "+cpsFlow.getResult())
                            .append(", FlowExecution PersistedClean: "+cpsFlow.persistedClean).append('\n');
                }
                throw new TimeoutException("Build didn't resume or fail in a timely fashion. "+builder.toString());
            }
            Thread.sleep(100L);
        }
    }

    /** Verifies all the universal post-build cleanup was done, regardless of pass/fail state. */
    static void verifyCompletedCleanly(Jenkins j, WorkflowRun run) throws Exception {
        // Assert that we have the appropriate flow graph entries
        FlowExecution exec = run.getExecution();
        List<FlowNode> heads = exec.getCurrentHeads();
        Assert.assertEquals(1, heads.size());
        verifyNoTasksRunning(j);
        Assert.assertEquals(0, exec.getCurrentExecutions(false).get().size());

        if (exec instanceof CpsFlowExecution) {
            CpsFlowExecution cpsFlow = (CpsFlowExecution)exec;
            assert cpsFlow.getStorage() != null;
            Assert.assertFalse("Should always be able to retrieve script", StringUtils.isEmpty(cpsFlow.getScript()));
            Assert.assertNull("We should have no Groovy shell left or that's a memory leak", cpsFlow.getShell());
            Assert.assertNull("We should have no Groovy shell left or that's a memory leak", cpsFlow.getTrustedShell());
            Assert.assertTrue(cpsFlow.done);
            assert cpsFlow.isComplete();
            assert cpsFlow.heads.size() == 1;
            Map.Entry<Integer, FlowHead> finalHead = cpsFlow.heads.entrySet().iterator().next();
            assert finalHead.getValue().get() instanceof FlowEndNode;
            Assert.assertEquals(cpsFlow.storage.getNode(finalHead.getValue().get().getId()), finalHead.getValue().get());
        }

        verifyExecutionRemoved(run);
    }

    private static void assertNoTasksRunning(Jenkins j) {
        j.getQueue().maintain();
        assert j.getQueue().isEmpty();
        Computer[] computerList = j.getComputers();
        for (Computer c : computerList) {
            List<Executor> executors = c.getExecutors();
            for (Executor ex : executors) {
                if (ex.isBusy()) {
                    Assert.fail("Computer "+c+" has an Executor "+ex+" still running a task: "+ex.getCurrentWorkUnit());
                }
            }
        }
    }

    /** Verifies we have nothing left that uses an executor for a given job. */
    static void verifyNoTasksRunning(Jenkins j) throws Exception {
        try {
            assertNoTasksRunning(j);
        } catch (AssertionError ae) {
            // Allows for slightly delayed processes
            Thread.sleep(1000L);
            assertNoTasksRunning(j);
        }
    }

    private static void verifyExecutionRemoved(WorkflowRun run) throws Exception{
        // Verify we've removed all FlowExcecutionList entries
        FlowExecutionList list = FlowExecutionList.get();
        for (FlowExecution fe : list) {
            if (fe == run.getExecution()) {
                Assert.fail("Run still has an execution in the list and should be removed!");
            }
        }
        Field f = list.getClass().getDeclaredField("runningTasks");
        f.setAccessible(true);
        CopyOnWriteList<FlowExecutionOwner> runningTasks = (CopyOnWriteList<FlowExecutionOwner>)(f.get(list));
        Assert.assertFalse(runningTasks.contains(run.asFlowExecutionOwner()));
    }


}
