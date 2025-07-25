/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Jorg Heymans
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.XmlFile;
import hudson.model.listeners.SaveableListener;
import hudson.remoting.Callable;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BuildTrigger;
import hudson.tasks.Builder;
import hudson.tasks.Fingerprinter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import jenkins.model.ArtifactManager;
import jenkins.model.ArtifactManagerConfiguration;
import jenkins.model.ArtifactManagerFactory;
import jenkins.model.ArtifactManagerFactoryDescriptor;
import jenkins.model.Jenkins;
import jenkins.util.VirtualFile;
import org.htmlunit.ScriptResult;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.DataBoundConstructor;

@Tag("SmokeTest")
@WithJenkins
class RunTest  {

    private static final Logger LOGGER = Logger.getLogger(RunTest.class.getName());

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Issue("JENKINS-17935")
    @Test
    void getDynamicInvisibleTransientAction() throws Exception {
        TransientBuildActionFactory.all().add(0, new TransientBuildActionFactory() {
            @Override public Collection<? extends Action> createFor(Run target) {
                return Collections.singleton(new Action() {
                    @Override public String getDisplayName() {
                        return "Test";
                    }

                    @Override public String getIconFileName() {
                        return null;
                    }

                    @Override public String getUrlName() {
                        return null;
                    }
                });
            }
        });
        j.buildAndAssertSuccess(j.createFreeStyleProject("stuff"));
        j.createWebClient().assertFails("job/stuff/1/nonexistent", HttpURLConnection.HTTP_NOT_FOUND);
    }

    @Issue("JENKINS-40281")
    @Test
    void getBadgeActions() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        assertEquals(0, b.getBadgeActions().size());
        assertTrue(b.canToggleLogKeep());
        b.keepLog();
        List<BuildBadgeAction> badgeActions = b.getBadgeActions();
        assertEquals(1, badgeActions.size());
        assertEquals(Run.KeepLogBuildBadge.class, badgeActions.get(0).getClass());
    }

    @Issue("JENKINS-51819")
    @Test
    void deleteArtifactsCustom() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(new Mgr.Factory());
        FreeStyleProject p = j.createFreeStyleProject();
        j.jenkins.getWorkspaceFor(p).child("f").write("", null);
        p.getPublishersList().add(new ArtifactArchiver("f"));
        FreeStyleBuild b = j.buildAndAssertSuccess(p);
        b.delete();
        assertTrue(Mgr.deleted.get());
        assertTrue(ExtensionList.lookupSingleton(SaveableListenerImpl.class).deleted);
    }

    @TestExtension("deleteArtifactsCustom")
    public static class SaveableListenerImpl extends SaveableListener {
        boolean deleted;

        @Override
        public void onDeleted(Saveable o, XmlFile file) {
            deleted = true;
        }
    }

    @Issue("JENKINS-73835")
    @Test
    void buildsMayNotBeDeletedWhileRunning() throws Exception {
        var p = j.createFreeStyleProject();
        p.getBuildersList().add(new SleepBuilder(999999));
        var b = p.scheduleBuild2(0).waitForStart();
        var ex = assertThrows(IOException.class, b::delete);
        assertThat(ex.getMessage(), containsString("Unable to delete " + b + " because it is still running"));
        b.getExecutor().interrupt();
        j.waitForCompletion(b);
        b.delete(); // Works fine.
    }

    @Issue("SECURITY-1902")
    @Test
    void preventXssInBadgeTooltip() throws Exception {
        j.jenkins.setQuietPeriod(0);
        /*
         * The scenario to trigger is to have a build protected from deletion because of an upstream protected build.
         * Once we have that condition, we need to ensure the upstream project has a dangerous name
         */
        FreeStyleProject up = j.createFreeStyleProject("up");
        up.getBuildersList().add(new WriteFileStep());
        up.getPublishersList().add(new Fingerprinter("**/*"));

        FullNameChangingProject down = j.createProject(FullNameChangingProject.class, "down");
        down.getBuildersList().add(new WriteFileStep());
        down.getPublishersList().add(new Fingerprinter("**/*"));
        // protected field, we are in the same package
        down.keepDependencies = true;

        up.getPublishersList().add(new BuildTrigger(down.getFullName(), false));

        j.jenkins.rebuildDependencyGraph();

        FreeStyleBuild upBuild = j.buildAndAssertSuccess(up);
        j.waitUntilNoActivity();
        CustomBuild downBuild = down.getBuilds().getLastBuild();
        assertNotNull(downBuild, "The down build must exist, otherwise the up's one is not protected.");

        // updating the name before the build is problematic under Windows
        // so we are updating internal stuff manually
        String newName = "Down<img src=x onerror=alert(123)>Project";
        down.setVirtualName(newName);
        Fingerprint f = upBuild.getAction(Fingerprinter.FingerprintAction.class).getFingerprints().get("test.txt");
        f.add(newName, 1);

        // keeping the minimum to validate it's working and it's not exploitable as there are some modifications
        // like adding double quotes
        // Some test flakes due to JavaScript objects not yet available
        // Wait 2 seconds before checking the assertion
        Thread.sleep(2003);
        ensureXssIsPrevented(up, "Down", "<img");
    }

    private void ensureXssIsPrevented(FreeStyleProject upProject, String validationPart, String dangerousPart) throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage htmlPage = wc.goTo(upProject.getUrl());

        // trigger the tooltip display
        htmlPage.executeJavaScript("document.querySelector('#jenkins-build-history .app-builds-container__item__inner__controls svg')._tippy.show()");
        wc.waitForBackgroundJavaScript(500);
        ScriptResult result = htmlPage.executeJavaScript("document.querySelector('.tippy-content').innerHTML;");
        Object jsResult = result.getJavaScriptResult();
        assertThat(jsResult, instanceOf(String.class));
        String jsResultString = (String) jsResult;

        assertThat("The tooltip does not work as expected", jsResultString, containsString(validationPart));
        assertThat("XSS not prevented", jsResultString, not(containsString(dangerousPart)));
    }

    public static class WriteFileStep extends Builder {
        @Override
        public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
            build.getWorkspace().child("test.txt").write("123", StandardCharsets.UTF_8.name());
            return true;
        }
    }

    public static class CustomBuild extends Build<FullNameChangingProject, CustomBuild> {
        @SuppressWarnings("checkstyle:redundantmodifier")
        public CustomBuild(FullNameChangingProject job) throws IOException {
            super(job);
        }
    }

    static class FullNameChangingProject extends Project<FullNameChangingProject, CustomBuild> implements TopLevelItem {
        private volatile String virtualName;

        FullNameChangingProject(ItemGroup parent, String name) {
            super(parent, name);
        }

        public void setVirtualName(String virtualName) {
            this.virtualName = virtualName;
        }

        @Override
        public String getName() {
            if (virtualName != null) {
                return virtualName;
            } else {
                return super.getName();
            }
        }

        @Override
        protected Class<CustomBuild> getBuildClass() {
            return CustomBuild.class;
        }

        @Override
        public TopLevelItemDescriptor getDescriptor() {
            return (FreeStyleProject.DescriptorImpl) Jenkins.get().getDescriptorOrDie(getClass());
        }

        @TestExtension("preventXssInBadgeTooltip")
        public static class DescriptorImpl extends AbstractProjectDescriptor {

            @Override
            public FullNameChangingProject newInstance(ItemGroup parent, String name) {
                return new FullNameChangingProject(parent, name);
            }
        }
    }

    public static final class Mgr extends ArtifactManager {
        static final AtomicBoolean deleted = new AtomicBoolean();

        @Override public boolean delete() {
            return !deleted.getAndSet(true);
        }

        @Override public void onLoad(Run<?, ?> build) {}

        @Override public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts) {}

        @Override public VirtualFile root() {
            return VirtualFile.forFile(Jenkins.get().getRootDir()); // irrelevant
        }

        public static final class Factory extends ArtifactManagerFactory {
            @SuppressWarnings("checkstyle:redundantmodifier") @DataBoundConstructor public Factory() {}

            @Override public ArtifactManager managerFor(Run<?, ?> build) {
                return new Mgr();
            }

            @TestExtension("deleteArtifactsCustom") public static final class DescriptorImpl extends ArtifactManagerFactoryDescriptor {}
        }
    }

    @Test
    void slowArtifactManager() throws Exception {
        ArtifactManagerConfiguration.get().getArtifactManagerFactories().add(new SlowMgr.Factory());
        var p = j.createFreeStyleProject();
        j.jenkins.getWorkspaceFor(p).child("f").write("", null);
        p.getPublishersList().add(new ArtifactArchiver("f"));
        var b = j.buildAndAssertSuccess(p);
        assertThat(b.getArtifactManager(), isA(SlowMgr.class));
        var wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        wc.getPage(b);
        wc.getPage(p);
    }

    public static final class SlowMgr extends ArtifactManager {
        static final AtomicBoolean deleted = new AtomicBoolean();

        @Override public boolean delete() {
            return !deleted.getAndSet(true);
        }

        @Override public void onLoad(Run<?, ?> build) {}

        @Override public void archive(FilePath workspace, Launcher launcher, BuildListener listener, Map<String, String> artifacts) {
            LOGGER.info(() -> "Pretending to archive " + artifacts);
        }

        @Override public VirtualFile root() {
            return new VirtualFile() {
                @Override public <V> V run(Callable<V, IOException> callable) throws IOException {
                    LOGGER.info("Sleeping");
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException x) {
                        throw new IOException(x);
                    }
                    throw new IllegalStateException();
                }

                @Override public String getName() {
                    return "";
                }

                @Override public URI toURI() {
                    return URI.create("no://where");
                }

                @Override public VirtualFile getParent() {
                    return null;
                }

                @Override public boolean isDirectory() throws IOException {
                    return true;
                }

                @Override public boolean isFile() throws IOException {
                    return false;
                }

                @Override public boolean exists() throws IOException {
                    return true;
                }

                @Override public VirtualFile[] list() throws IOException {
                    return new VirtualFile[0];
                }

                @Override public VirtualFile child(String name) {
                    return null;
                }

                @Override public long length() throws IOException {
                    return 0;
                }

                @Override public long lastModified() throws IOException {
                    return 0;
                }

                @Override public boolean canRead() throws IOException {
                    return true;
                }

                @Override public InputStream open() throws IOException {
                    throw new FileNotFoundException();
                }
            };
        }

        public static final class Factory extends ArtifactManagerFactory {
            @SuppressWarnings("checkstyle:redundantmodifier") @DataBoundConstructor public Factory() {}

            @Override public ArtifactManager managerFor(Run<?, ?> build) {
                LOGGER.info(() -> "Picking manager for " + build);
                return new SlowMgr();
            }

            @TestExtension("slowArtifactManager") public static final class DescriptorImpl extends ArtifactManagerFactoryDescriptor {}
        }
    }

}
