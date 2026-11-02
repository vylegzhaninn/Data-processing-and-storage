package spider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

/**
 * Страница, отдаваемая сервером на GET-запрос: текст сообщения и список путей-наследников.
 * Формат тела: {@code {"message": "...", "successors": ["path_1", ...]}}.
 */
public record Page(String message, List<String> successors) {

    /**
     * Разбирает JSON-тело ответа в {@link Page}.
     * Поле {@code successors} при отсутствии или null заменяется пустым неизменяемым списком.
     *
     * @param body   тело HTTP-ответа в формате JSON
     * @param mapper ObjectMapper для разбора
     * @return разобранная страница
     */
    static Page parse(String body, ObjectMapper mapper) {
        try {
            var tree = mapper.readTree(body);
            String message = tree.path("message").asText("");
            var successorsNode = tree.get("successors");
            List<String> successors = successorsNode == null || successorsNode.isNull()
                    ? List.of()
                    : List.copyOf(mapper.convertValue(successorsNode,
                            mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
            return new Page(message, Collections.unmodifiableList(successors));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось разобрать JSON-тело страницы: " + e.getMessage(), e);
        }
    }
}
