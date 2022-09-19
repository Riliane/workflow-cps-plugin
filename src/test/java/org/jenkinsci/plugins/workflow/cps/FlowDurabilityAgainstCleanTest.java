package org.jenkinsci.plugins.workflow.cps;

import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;

public class FlowDurabilityAgainstCleanTest extends AbstractFlowDurabilityTest {


    /** Verify that if the controller dies messily and we're not durable against that, build fails cleanly.
     */
    @Test
    public void testDurableAgainstCleanRestartFailsWithDirtyShutdown() throws Exception {
        final String[] logStart = new String[1];
        story.addStepWithDirtyShutdown(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, "durableAgainstClean", FlowDurabilityHint.PERFORMANCE_OPTIMIZED, false);
                Assert.assertEquals(FlowDurabilityHint.PERFORMANCE_OPTIMIZED, run.getExecution().getDurabilityHint());
                logStart[0] = JenkinsRule.getLog(run);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName("durableAgainstClean", WorkflowJob.class).getLastBuild();
                if (run == null) { return; } //there is a small chance due to non atomic write that build.xml will be empty and the run won't load at all
                verifyFailedCleanly(story.j.jenkins, run);
                story.j.assertLogContains(logStart[0], run);
            }
        });
    }
}
