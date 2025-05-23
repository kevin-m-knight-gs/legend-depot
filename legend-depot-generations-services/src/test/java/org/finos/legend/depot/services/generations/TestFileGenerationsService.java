//  Copyright 2021 Goldman Sachs
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

package org.finos.legend.depot.services.generations;

import org.finos.legend.depot.domain.generation.DepotGeneration;
import org.finos.legend.depot.store.model.projects.StoreProjectData;
import org.finos.legend.depot.store.model.projects.StoreProjectVersionData;
import org.finos.legend.depot.services.api.generations.ManageFileGenerationsService;
import org.finos.legend.depot.services.api.projects.ProjectsService;
import org.finos.legend.depot.services.generations.impl.ManageFileGenerationsServiceImpl;
import org.finos.legend.depot.services.generations.loader.FileGenerationLoader;
import org.finos.legend.depot.services.projects.ProjectsServiceImpl;
import org.finos.legend.depot.services.api.projects.configuration.ProjectsConfiguration;
import org.finos.legend.depot.store.api.generations.UpdateFileGenerations;
import org.finos.legend.depot.store.api.projects.UpdateProjects;
import org.finos.legend.depot.store.api.projects.UpdateProjectsVersions;
import org.finos.legend.depot.services.api.metrics.query.QueryMetricsRegistry;
import org.finos.legend.depot.store.model.generations.StoredFileGeneration;
import org.finos.legend.depot.store.mongo.TestStoreMongo;
import org.finos.legend.depot.store.mongo.generations.FileGenerationsMongo;
import org.finos.legend.depot.services.api.notifications.queue.Queue;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.finos.legend.depot.domain.version.VersionValidator.BRANCH_SNAPSHOT;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


public class TestFileGenerationsService extends TestStoreMongo
{

    public static final String AVRO = "avro";
    private static final URL filePath = TestFileGenerationsService.class.getClassLoader().getResource("generations/test-file-generation-master-SNAPSHOT.jar");
    private UpdateFileGenerations generations = new FileGenerationsMongo(mongoProvider);
    UpdateProjectsVersions projectsVersionsStore = mock(UpdateProjectsVersions.class);
    UpdateProjects projectsStore = mock(UpdateProjects.class);
    private final QueryMetricsRegistry metrics = mock(QueryMetricsRegistry.class);
    private final Queue queue = mock(Queue.class);
    private ProjectsService projectsService = new ProjectsServiceImpl(projectsVersionsStore, projectsStore, metrics, queue, new ProjectsConfiguration("master"));
    private ManageFileGenerationsService service = new ManageFileGenerationsServiceImpl(generations, projectsService);

    @BeforeEach
    public void loadData() throws Exception
    {

        try (FileGenerationLoader file = FileGenerationLoader.newFileGenerationsLoader(new File(filePath.toURI())))
        {
            Stream<DepotGeneration> data = file.getAllFileGenerations();
            Assertions.assertNotNull(data);
            data.forEach(gen ->
            {
                if (gen.getPath().startsWith("/examples/metadata"))
                {
                    DepotGeneration generation = new DepotGeneration(gen.getPath(), gen.getContent());
                    generations.createOrUpdate(Arrays.asList(new StoredFileGeneration("group.test", "test",  BRANCH_SNAPSHOT("master"), null, null, generation),
                                               new StoredFileGeneration("group.test", "test", "1.0.0", "examples::metadata::test::ClientBasic", null, generation),
                                               new StoredFileGeneration("group.test.otherproject", "test", "1.0.0", "examples::metadata::test::ClientBasic", null, generation)));
                }
                else
                {
                    DepotGeneration generation = new DepotGeneration(gen.getPath().replace("examples_avrogen/", ""), gen.getContent());
                    generations.createOrUpdate(Arrays.asList(new StoredFileGeneration("group.test", "test", BRANCH_SNAPSHOT("master"), "examples::avrogen", AVRO, generation),
                                                new StoredFileGeneration("group.test", "test", "1.0.1", "examples::avrogen", AVRO, generation),
                                                new StoredFileGeneration("group.test", "test", "1.0.0", "examples::avrogen", AVRO, generation),
                                                new StoredFileGeneration("group.test.otherproject", "test", "1.0.0", "examples::avrogen1", AVRO, generation)));
                }
            });

            Assertions.assertEquals(54, generations.getAll().size());
        }

        when(projectsStore.find("group.test","test")).thenReturn(Optional.of(new StoreProjectData("prod-1","group.test","test",null,"1.0.0")));
        when(projectsStore.find("group.test.otherproject", "test")).thenReturn(Optional.of(new StoreProjectData("prod-2","group.test.otherproject", "test")));
        when(projectsVersionsStore.find("group.test","test",BRANCH_SNAPSHOT("master"))).thenReturn(Optional.of(new StoreProjectVersionData("group-test","test",BRANCH_SNAPSHOT("master"))));
        when(projectsVersionsStore.find("group.test","test","1.0.0")).thenReturn(Optional.of(new StoreProjectVersionData("group-test","test","1.0.0")));
        when(projectsVersionsStore.find("group.test","test")).thenReturn(Arrays.asList(new StoreProjectVersionData("group-test","test","1.0.0")));
        when(projectsVersionsStore.find("i.dont","exist","version")).thenReturn(Optional.empty());
        when(projectsVersionsStore.find("group.test.otherproject", "test","1.0.0")).thenReturn(Optional.of(new StoreProjectVersionData("group-test","test","1.0.0")));
    }

    @Test
    public void canDelete()
    {

        service.delete("group.test", "test",BRANCH_SNAPSHOT("master"));
        Assertions.assertEquals(40, generations.getAll().size());
        service.delete("group.test.otherproject", "test", "1.0.0");
        Assertions.assertEquals(26, generations.getAll().size());
        try
        {
            service.delete("group.test", "test111", "1.1.0");
            Assertions.fail("exception expected");
        }
        catch (IllegalArgumentException e)
        {
            Assertions.assertTrue(true);
        }
        Assertions.assertEquals(26, generations.getAll().size());

    }

    @Test
    public void canQueryFileGenerationEntities()
    {

        List<DepotGeneration> generations = service.getFileGenerations("group.test", "test",BRANCH_SNAPSHOT("master"));
        Assertions.assertEquals(14, generations.size());

        List<DepotGeneration> gens1 = service.getFileGenerations("group.test", "test", "1.0.0");
        Assertions.assertEquals(14, gens1.size());

        try
        {
            List<DepotGeneration> gens2 = service.getFileGenerations("group.test.other", "test", "1.0.0");
            Assertions.fail("Exception expected");
        }
        catch (IllegalArgumentException e)
        {
            Assertions.assertTrue(true);
        }
    }

    @Test
    public void canQueryFileGenerationEntitiesByElementPath()
    {
        Assertions.assertEquals(12, service.getFileGenerationsByElementPath("group.test", "test", "1.0.0", "examples::avrogen").size());
        Assertions.assertTrue(service.getFileGenerationsByElementPath("group.test", "test", "1.0.0", "examples::avrogen1").isEmpty());
        Assertions.assertEquals(2, service.getFileGenerationsByElementPath("group.test", "test", "1.0.0", "examples::metadata::test::ClientBasic").size());
    }

    @Test
    public void canGetByType()
    {
        List<StoredFileGeneration> fileGenerations = service.findByType("group.test", "test", "1.0.0", AVRO);
        Assertions.assertEquals(12, fileGenerations.size());
        Assertions.assertEquals(0, service.findByTypeAndElementPath("group.test", "test", "1.0.0", AVRO, "element::NotFound").size());
        Assertions.assertEquals(12, service.findByTypeAndElementPath("group.test", "test", "1.0.0", AVRO, "examples::avrogen").size());
    }

    @Test
    public void canQueryFileGenerationEntitiesByFilePath()
    {
        Assertions.assertTrue(service.getFileGenerationsByFilePath("group.test", "test", "1.0.0", "/examples/metadata/test/ClientBasic.avro").isPresent());
        Assertions.assertTrue(service.getFileGenerationsByFilePath("group.test", "test", "1.0.0", "/examples/metadata/test/ClientBasic/my-ext/Output1.txt").isPresent());
        Assertions.assertTrue(service.getFileGenerationsByFilePath("group.test", "test", "1.0.0", "/examples/metadata/test/ClientBasic/my-ext/Output2.txt").isPresent());
        Assertions.assertTrue(service.getFileGenerationsByFilePath("group.test.otherproject", "test", "1.0.0", "/examples/metadata/test/ClientBasic.avro").isPresent());

        Assertions.assertFalse(service.getFileGenerationsByFilePath("group.test", "test", "1.0.0", "bad").isPresent());
        Assertions.assertFalse(service.getFileGenerationsByFilePath("group.test", "test", "1.0.0", "/examples/metadata/test/ClientBasic/my-ext/DND.txt").isPresent());
    }

    @Test
    public void canQueryFileGenerationEntitiesByFileContent()
    {
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "1.0.0", "/examples/metadata/test/ClientBasic.avro").isPresent());
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "1.0.0", "/examples/metadata/test/ClientBasic/my-ext/Output1.txt").isPresent());
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "1.0.0", "/examples/metadata/test/ClientBasic/my-ext/Output2.txt").isPresent());
        Assertions.assertTrue(service.getFileGenerationsByFilePath("group.test", "test",BRANCH_SNAPSHOT("master"),  "/examples/metadata/test/ClientBasic.avro").isPresent());
    }

    @Test
    public void cantGetGenerationsForNonExistentProject()
    {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.getFileGenerations("i.dont","exist","version"));
    }

    @Test
    public void cantGetGenerationsForNonExistentVersion()
    {
        Assertions.assertThrows(IllegalArgumentException.class, () -> service.getFileGenerations("group.test","test","10.0.0"));
    }

    @Test
    public void canQueryFileGenerationEntitiesWithLatestAlias()
    {

        List<DepotGeneration> gens1 = service.getFileGenerations("group.test", "test", "latest");
        Assertions.assertEquals(14, gens1.size());
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "latest", "/examples/metadata/test/ClientBasic.avro").isPresent());
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "latest", "/examples/metadata/test/ClientBasic/my-ext/Output1.txt").isPresent());
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "latest", "/examples/metadata/test/ClientBasic/my-ext/Output2.txt").isPresent());
        Assertions.assertEquals(2, service.getFileGenerationsByElementPath("group.test", "test", "latest", "examples::metadata::test::ClientBasic").size());
        Assertions.assertEquals(12, service.getFileGenerationsByElementPath("group.test", "test", "latest", "examples::avrogen").size());
    }

    @Test
    public void canQueryFileGenerationEntitiesWithHeadAlias()
    {

        List<DepotGeneration> gens1 = service.getFileGenerations("group.test", "test", "head");
        Assertions.assertEquals(14, gens1.size());
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "head", "/examples/metadata/test/ClientBasic.avro").isPresent());
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "head", "/examples/metadata/test/ClientBasic/my-ext/Output1.txt").isPresent());
        Assertions.assertTrue(service.getFileGenerationContentByFilePath("group.test", "test", "head", "/examples/metadata/test/ClientBasic/my-ext/Output2.txt").isPresent());
    }
}