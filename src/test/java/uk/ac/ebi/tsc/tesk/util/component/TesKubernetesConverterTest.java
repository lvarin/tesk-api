package uk.ac.ebi.tsc.tesk.util.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import io.kubernetes.client.models.V1Job;
import io.kubernetes.client.models.V1PodList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.json.JsonContentAssert;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;
import uk.ac.ebi.tsc.tesk.TestUtils;
import uk.ac.ebi.tsc.tesk.config.GsonConfig;
import uk.ac.ebi.tsc.tesk.config.KubernetesObjectsSupplier;
import uk.ac.ebi.tsc.tesk.config.TaskmasterEnvProperties;
import uk.ac.ebi.tsc.tesk.config.security.User;
import uk.ac.ebi.tsc.tesk.model.TesState;
import uk.ac.ebi.tsc.tesk.model.TesTask;
import uk.ac.ebi.tsc.tesk.util.constant.Constants;
import uk.ac.ebi.tsc.tesk.util.data.TaskBuilder;

import java.io.*;
import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.BDDMockito.given;
import static uk.ac.ebi.tsc.tesk.util.constant.Constants.LABEL_TASKSTATE_KEY;
import static uk.ac.ebi.tsc.tesk.util.constant.Constants.LABEL_TASKSTATE_VALUE_CANC;


/**
 * @author Ania Niewielska <aniewielska@ebi.ac.uk>
 */
@RunWith(SpringRunner.class)
@JsonTest
@TestPropertySource(locations = {"classpath:application.properties"},
        properties = {"tesk.api.taskmaster.image-name = task-full-image-name",
                "tesk.api.taskmaster.image-version = task-full-image-version",
                "tesk.api.taskmaster.filer-image-version = task-full-filer-image-version",
                "tesk.api.taskmaster.filer-image-name = task-full-filer-image-name",
                "tesk.api.taskmaster.ftp.secret-name = secretstorage",
                "tesk.api.taskmaster.service-account-name = custom-service-account",
                "tesk.api.taskmaster.debug = true",
                "tesk.api.taskmaster.environment.wes.base.path = /usr/sth/path",
                "tesk.api.taskmaster.environment.TES_BASE_PATH = /tesk/share",
                "tesk.api.taskmaster.executor-secret.name = mysecret",
                "tesk.api.taskmaster.executor-secret.mount-path = /etc/secret"
        })
@EnableConfigurationProperties(TaskmasterEnvProperties.class)
public class TesKubernetesConverterTest {

    @MockBean
    private JobNameGenerator jobNameGenerator;

    @TestConfiguration
    @Import({KubernetesObjectsSupplier.class, GsonConfig.class})
    static class Configuration {
    }

    @Autowired
    @Qualifier("executor")
    Supplier<V1Job> executorTemplateSupplier;

    @Autowired
    @Qualifier("taskmaster")
    Supplier<V1Job> taskmasterTemplateSupplier;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Gson gson;


    private TesKubernetesConverter converter;

    @Before
    public void setUpConverter() {

        this.converter = new TesKubernetesConverter(executorTemplateSupplier, taskmasterTemplateSupplier,
                objectMapper, gson);
    }

    @Test
    public void fromTesTaskToK8sJob() throws IOException {

        given(this.jobNameGenerator.getTaskMasterName()).willReturn("task-35605447");
        Resource inputTaskFile = new ClassPathResource("fromTesToK8s/task.json");
        TesTask inputTask;
        try (InputStream inputStream = inputTaskFile.getInputStream();
             Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            inputTask = this.objectMapper.readValue(reader, TesTask.class);
        }
        V1Job outputJob = this.converter.fromTesTaskToK8sJob(inputTask, User.builder("test-user-id").teskMemberedGroups(StringUtils.commaDelimitedListToSet("ABC,CDE")).build());
        assertEquals(outputJob.getMetadata().getAnnotations().get("tes-task-name"), "taskFull");
        //testing annotation with entire serialized task content..
        assertThat(outputJob.getMetadata().getAnnotations().get("json-input"), is(not(isEmptyOrNullString())));
        //..comparing jsons
        JsonContentAssert annotationWithEntireTask = new JsonContentAssert(this.getClass(), outputJob.getMetadata().getAnnotations().get("json-input"));
        annotationWithEntireTask.isEqualToJson(inputTaskFile);
        //..and one example path
        annotationWithEntireTask.extractingJsonPathArrayValue("@.executors[1].command").startsWith("sh");
        //..and an example path with renamed field
        annotationWithEntireTask.extractingJsonPathNumberValue("@.resources.cpu_cores").isEqualTo(4);
        assertEquals(outputJob.getMetadata().getLabels().get("job-type"), "taskmaster");
        assertEquals(outputJob.getMetadata().getLabels().get("creator-user-id"), "test-user-id");
        assertEquals(outputJob.getMetadata().getLabels().get("creator-group-name"), "ABC");
        //test of placing generated task ID
        assertEquals(outputJob.getMetadata().getName(), "task-35605447");
        assertEquals(outputJob.getSpec().getTemplate().getMetadata().getName(), "task-35605447");
        assertEquals(outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getName(), "task-35605447");

        assertEquals(outputJob.getSpec().getTemplate().getSpec().getServiceAccountName(), "custom-service-account");
        assertEquals(outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(0), "$(JSON_INPUT)");
        assertEquals(outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(2), "default");
        assertEquals(outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(4), "task-full-filer-image-version");
        assertEquals(outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().get(6), "task-full-filer-image-name");
        assertEquals(outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getImage(), "task-full-image-name:task-full-image-version");
        assertEquals(outputJob.getSpec().getTemplate().getSpec().getRestartPolicy(), "Never");

        JsonContentAssert taskMasterInputJson = new JsonContentAssert(this.getClass(), outputJob.getSpec().getTemplate().getSpec().
                getContainers().get(0).getEnv().stream().filter(env -> env.getName().equals("JSON_INPUT")).findAny().get().getValue());
        taskMasterInputJson.hasJsonPathValue("outputs[?(@.type == 'FILE')]");
        //JSONPath filter expressions sadly return array ;/
        taskMasterInputJson.extractingJsonPathArrayValue("outputs[?(@.type == 'FILE')].url").containsExactly("/path/to/output_file.txt");
        taskMasterInputJson.extractingJsonPathArrayValue("outputs[?(@.type == 'DIRECTORY')].path").containsExactly("/outputs/output");
        taskMasterInputJson.extractingJsonPathNumberValue("inputs.size()").isEqualTo(3);
        taskMasterInputJson.extractingJsonPathArrayValue("inputs[?(@.name == 'input1')].path").containsExactly("/some/volume/input.txt");
        taskMasterInputJson.extractingJsonPathArrayValue("inputs[?(@.url == 'http://example.org/resources')].path").containsExactly("/tes/volumes/input");
        taskMasterInputJson.extractingJsonPathArrayValue("inputs[?(@.content)].content").containsExactly("aaabbbcccddd");
        taskMasterInputJson.extractingJsonPathNumberValue("volumes.size()").isEqualTo(2);
        taskMasterInputJson.extractingJsonPathArrayValue("volumes").contains("/tmp/tmp2");
        taskMasterInputJson.extractingJsonPathArrayValue("executors[*].metadata.annotations['tes-task-name'])").containsOnly("taskFull").hasSize(2);
        taskMasterInputJson.extractingJsonPathArrayValue("executors[*].metadata.labels['job-type']").containsOnly("executor").hasSize(2);
        taskMasterInputJson.extractingJsonPathArrayValue("executors[*].metadata.labels['taskmaster-name']").containsOnly("task-35605447").hasSize(2);
        taskMasterInputJson.extractingJsonPathArrayValue("executors[*].metadata.labels['creator-user-id']").containsOnly("test-user-id").hasSize(2);

        taskMasterInputJson.extractingJsonPathStringValue("executors[0].metadata.name").isEqualTo("task-35605447-ex-00");
        taskMasterInputJson.extractingJsonPathStringValue("executors[0].spec.template.metadata.name").isEqualTo("task-35605447-ex-00");
        taskMasterInputJson.extractingJsonPathStringValue("executors[0].spec.template.spec.containers[0].name").isEqualTo("task-35605447-ex-00");
        taskMasterInputJson.extractingJsonPathStringValue("executors[1].metadata.name").isEqualTo("task-35605447-ex-01");
        taskMasterInputJson.extractingJsonPathStringValue("executors[1].spec.template.metadata.name").isEqualTo("task-35605447-ex-01");
        taskMasterInputJson.extractingJsonPathStringValue("executors[1].spec.template.spec.containers[0].name").isEqualTo("task-35605447-ex-01");

        taskMasterInputJson.extractingJsonPathArrayValue("executors[*].spec.template.spec.restartPolicy").containsOnly("Never").hasSize(2);
        taskMasterInputJson.extractingJsonPathArrayValue("executors[*].spec.template.spec.containers[0].resources.requests.cpu").containsOnly("4").hasSize(2);
        taskMasterInputJson.extractingJsonPathArrayValue("executors[*].spec.template.spec.containers[0].resources.requests.memory").containsOnly("15.000G").hasSize(2);

        taskMasterInputJson.extractingJsonPathMapValue("executors[0].spec.template.spec.containers[0]").containsOnlyKeys("name", "image", "command", "resources", "volumeMounts");
        taskMasterInputJson.extractingJsonPathMapValue("executors[1].spec.template.spec.containers[0]").containsOnlyKeys("name", "image", "command", "resources", "workingDir", "env", "volumeMounts");
        taskMasterInputJson.extractingJsonPathArrayValue("executors[0].spec.template.spec.containers[0].command").containsExactly("/bin/sh", "-c", "echo 'hello world' > /tmp/stdout");
        taskMasterInputJson.extractingJsonPathArrayValue("executors[1].spec.template.spec.containers[0].command").containsExactly("/bin/sh", "-c", "sh -c 'md5sum $src' > /tes/output.txt 2> /tes/err.txt");
        taskMasterInputJson.extractingJsonPathStringValue("executors[0].spec.template.spec.containers[0].image").isEqualTo("ubuntu");
        taskMasterInputJson.extractingJsonPathStringValue("executors[1].spec.template.spec.containers[0].image").isEqualTo("alpine");

        taskMasterInputJson.extractingJsonPathArrayValue("executors[1].spec.template.spec.containers[0].env[?(@.name == 'sth')].value").containsOnly("sthElse");
        taskMasterInputJson.extractingJsonPathStringValue("executors[1].spec.template.spec.containers[0].workingDir").isEqualTo("/starthere");

        taskMasterInputJson.extractingJsonPathNumberValue("resources.disk_gb").isEqualTo(100.0);

        taskMasterInputJson.isEqualToJson(new ClassPathResource("fromTesToK8s/taskmaster_param.json"), JSONCompareMode.NON_EXTENSIBLE);

        Resource outputJobFile = new ClassPathResource("fromTesToK8s/job.json");
        V1Job expectedJob;
        try (InputStream inputStream = outputJobFile.getInputStream();
             Reader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            expectedJob = this.gson.fromJson(reader, V1Job.class);
        }
        expectedJob.getMetadata().setAnnotations(null);
        outputJob.getMetadata().setAnnotations(null);
        expectedJob.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream().filter(env -> env.getName().equals(Constants.TASKMASTER_INPUT)).forEach(env -> env.setValue(""));
        outputJob.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream().filter(env -> env.getName().equals(Constants.TASKMASTER_INPUT)).forEach(env -> env.setValue(""));
        //comparing fields of resulting Job object and pattern Job objects other those with JSON values, which were cleared in previous lines (JSON strings do not have to be exactly equal to pattern).
        assertEquals(expectedJob, outputJob);
    }

    private TaskBuilder prepareBaseTaskBuider() throws IOException {
        TaskBuilder taskBuilder = TaskBuilder.newSingleTask();
        taskBuilder.addJob(this.gson.fromJson(TestUtils.getFileContentFromResources("fromK8sToTes/taskmaster.json"), V1Job.class));
        taskBuilder.addJob(this.gson.fromJson(TestUtils.getFileContentFromResources("fromK8sToTes/executor.json"), V1Job.class));
        taskBuilder.addPodList(this.gson.fromJson(TestUtils.getFileContentFromResources("fromK8sToTes/executor_pods.json"), V1PodList.class).getItems());
        return taskBuilder;
    }

    private TesTask prepareBaseExpectedTask() throws IOException {
        return this.objectMapper.readValue(TestUtils.getFileContentFromResources("fromK8sToTes/task.json"), TesTask.class);
    }

    @Test
    public void fromK8sToTask() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        TesTask expectedTask = this.prepareBaseExpectedTask();
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), true);
        assertThat(outputTask, is(expectedTask));
    }

    @Test
    public void fromK8sToTask_cancelled() throws IOException {
        TaskBuilder taskBuilder = this.prepareBaseTaskBuider();
        taskBuilder.getTask().getTaskmaster().getJob().getMetadata().getLabels().putIfAbsent(LABEL_TASKSTATE_KEY, LABEL_TASKSTATE_VALUE_CANC);
        TesTask expectedTask = this.prepareBaseExpectedTask();
        expectedTask.setState(TesState.CANCELED);
        TesTask outputTask = this.converter.fromK8sJobsToTesTaskBasic(taskBuilder.getTask(), true);
        assertThat(outputTask, is(expectedTask));
    }

}
