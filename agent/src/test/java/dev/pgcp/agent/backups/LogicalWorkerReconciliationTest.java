package dev.pgcp.agent.backups;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.pgcp.agent.api.*;
import dev.pgcp.agent.config.AgentProperties;
import dev.pgcp.agent.docker.*;
import dev.pgcp.agent.ops.OperationService;
import tools.jackson.databind.json.JsonMapper;

class LogicalWorkerReconciliationTest {
    @TempDir Path data;
    @Test void completesNetworkAttachmentBeforeStartingAnAlreadyCreatedWorker() throws Exception {
        var docker=mock(DockerGateway.class);var commands=mock(CommandRunner.class);var locks=mock(OperationService.class);
        UUID id=UUID.randomUUID();String worker="pgcp-worker-"+id;
        var source=new ContainerInfo("a".repeat(64),"source","postgres:18","running",null,null,false,null,18,"18",Libc.GLIBC,List.of("source-network"),Map.of(),null);
        var created=new ContainerInfo("b".repeat(64),worker,"worker","created",null,null,false,null,18,"18",Libc.GLIBC,List.of("bridge"),Map.of("pgcp.logical-operation",id.toString()),null);
        when(docker.inspect("source")).thenReturn(Optional.of(source));when(docker.inspect(worker)).thenReturn(Optional.of(created));
        var started=new CountDownLatch(1);
        when(commands.run(anyList(),isNull(),any())).thenAnswer(i->{ List<String> argv=i.getArgument(0);if(argv.get(1).equals("start")) started.countDown();return new CommandRunner.Result(0,""); });
        var props=new AgentProperties(data,data.resolve("images/postgres"),null,null,Map.of(),0,0,"docker",Duration.ofMinutes(1),null);
        var service=new LogicalWorkerService(props,docker,commands,locks,JsonMapper.builder().build(),false,"bridge");
        try {
            var storage=new LogicalBackupRequest.Storage("https://s3.example","auto","backups","pgcp/test","access","secret","age1"+"q".repeat(58),null);
            service.start(new LogicalBackupRequest(id,LogicalBackupRequest.Action.BACKUP,UUID.randomUUID(),"source",source.id(),18,
                    new LogicalBackupRequest.Connection("source",5432,"app","backup","password","require",null),storage,
                    storage.prefix()+"/"+id+".dump.age",storage.prefix()+"/"+id+".manifest.age",null,null));
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            var order=inOrder(commands);
            order.verify(commands).run(eq(List.of("docker","network","connect","source-network",worker)),isNull(),any());
            order.verify(commands).run(eq(List.of("docker","start",worker)),isNull(),any());
            verify(commands,never()).run(argThat(a->a.contains("create")),any(),any());
        } finally { service.shutdown(); }
    }
}
