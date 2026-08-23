package com.example.agentservice.formatter;

import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.formatter.dashscope.dto.DashScopeMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Adds Qwen-Long file references as the second system message. */
public class QwenLongChatFormatter extends DashScopeChatFormatter {

    public static final String FILE_IDS_METADATA_KEY = "qwen_long_file_ids";

    @Override
    protected List<DashScopeMessage> doFormat(List<Msg> messages) {
        return super.doFormat(injectFileReference(messages));
    }

    private List<Msg> injectFileReference(List<Msg> messages) {
        List<String> fileIds = messages.stream()
                .map(Msg::getMetadata)
                .filter(metadata -> metadata != null)
                .map(metadata -> metadata.get(FILE_IDS_METADATA_KEY))
                .filter(List.class::isInstance)
                .flatMap(value -> ((List<?>) value).stream())
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(fileId -> !fileId.isBlank())
                .distinct()
                .collect(Collectors.toList());
        if (fileIds.isEmpty()) {
            return messages;
        }

        Msg fileReference = Msg.builder()
                .role(MsgRole.SYSTEM)
                .textContent(fileIds.stream()
                        .map(fileId -> "fileid://" + fileId)
                        .collect(Collectors.joining(",")))
                .build();

        List<Msg> formatted = new ArrayList<>(messages.size() + 1);
        boolean inserted = false;
        for (Msg message : messages) {
            formatted.add(message);
            if (!inserted && message.getRole() == MsgRole.SYSTEM) {
                formatted.add(fileReference);
                inserted = true;
            }
        }
        if (!inserted) {
            formatted.add(0, fileReference);
        }
        return formatted;
    }
}
