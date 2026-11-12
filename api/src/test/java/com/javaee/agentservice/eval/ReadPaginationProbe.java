package com.javaee.agentservice.eval;

import com.javaee.documentservice.client.CollabClient;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** What /ai/read really returns, before the gateway's character cap touches it. */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
class ReadPaginationProbe {

    private static final Logger log = LoggerFactory.getLogger(ReadPaginationProbe.class);
    private static final Long USER = 1L;

    @Autowired
    private DocumentService documentService;
    @Autowired
    private CollabClient collab;

    @Test
    void reportsHowManyBlocksALongDocumentHas() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER), null,
                        List.of(new SimpleGrantedAuthority("user"))));

        String plain = Files.readString(Path.of("target/eval/extracted/llama2.txt"));
        probe("纯文本", plain);
        probe("加了段落标签", "<p>" + plain.replace("\n\n", "</p><p>") + "</p>");
    }

    private void probe(String label, String content) {
        DocumentCreateDTO create = new DocumentCreateDTO();
        create.setTitle("分页探针-" + label);
        DocumentVO document = documentService.create(create, USER);

        DocumentUpdateDTO update = new DocumentUpdateDTO();
        update.setContent(content);
        update.setChangeLog("pagination probe");
        documentService.update(document.getId(), update, USER);

        for (int offset : new int[]{1, 2, 201}) {
            Map<String, Object> view = collab.read(document.getId(), USER, offset, 200);
            Object markdown = view.get("markdown");
            log.info("PAGE {} | {} chars stored | offset={} -> keys={} total={} "
                            + "from={} to={} markdownChars={}",
                    label, content.length(), offset, view.keySet(), view.get("total"),
                    view.get("from"), view.get("to"),
                    markdown == null ? 0 : markdown.toString().length());
        }
    }
}
