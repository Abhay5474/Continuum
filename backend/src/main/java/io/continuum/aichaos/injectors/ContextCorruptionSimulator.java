package io.continuum.aichaos.injectors;

import io.continuum.provider.model.Message;
import io.continuum.provider.model.Role;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Two related corruptions:
 *  - {@link #truncate} removes critical context (keeps only system + last user),
 *    simulating context-window overflow / lost-in-the-middle.
 *  - {@link #corruptMemory} mangles retrieved memory text before it is used.
 */
@Component
public class ContextCorruptionSimulator {

    public List<Message> truncate(List<Message> messages) {
        if (messages.size() <= 2) {
            return messages;
        }
        List<Message> result = new ArrayList<>();
        // Keep system messages and only the final user turn; drop the middle.
        Message lastUser = null;
        for (Message m : messages) {
            if (m.role() == Role.SYSTEM) {
                result.add(m);
            } else if (m.role() == Role.USER) {
                lastUser = m;
            }
        }
        if (lastUser != null) {
            result.add(lastUser);
        }
        return result;
    }

    public String corruptMemory(String memory) {
        if (memory == null || memory.isBlank()) {
            return memory;
        }
        // Drop the second half of the memory and scramble a key figure.
        String half = memory.substring(0, Math.max(1, memory.length() / 2));
        return half.replaceAll("\\d+", "9999") + " …[memory corrupted]";
    }
}
