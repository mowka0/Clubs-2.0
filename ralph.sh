#!/bin/bash
set -e

TASKS_FILE="tasks.json"

# Agent selection:
# - Set RALPH_AGENT=claude or RALPH_AGENT=codex to force.
# - Otherwise auto-detect (prefers Claude if available).
resolve_agent() {
    if [[ -n "${RALPH_AGENT:-}" ]]; then
        echo "$RALPH_AGENT"
        return 0
    fi
    if command -v claude >/dev/null 2>&1; then
        echo "claude"
        return 0
    fi
    if command -v codex >/dev/null 2>&1; then
        echo "codex"
        return 0
    fi
    return 1
}

run_agent() {
    local agent="$1"
    local prompt="$2"

    case "$agent" in
        claude)
            claude --dangerously-skip-permissions -p "$prompt"
            ;;
        codex)
            local output_file
            output_file="$(mktemp -t ralph_codex.XXXXXX)"
            codex exec --full-auto --color never -C "$PWD" --output-last-message "$output_file" "$prompt" >/dev/null
            cat "$output_file"
            rm -f "$output_file"
            ;;
        *)
            echo "Unsupported agent: $agent" >&2
            return 1
            ;;
    esac
}

# Функция проверки наличия pending задач
has_pending_tasks() {
    pending_count=$(grep -c '"status": "pending"' "$TASKS_FILE" 2>/dev/null || echo "0")
    [ "$pending_count" -gt 0 ]
}

iteration=1

while has_pending_tasks; do
    echo ""
    echo "========================================="
    echo "  Итерация $iteration"
    echo "========================================="

    # Показываем текущий статус задач
    pending=$(grep -c '"status": "pending"' "$TASKS_FILE" 2>/dev/null || echo "0")
    in_progress=$(grep -c '"status": "in_progress"' "$TASKS_FILE" 2>/dev/null || echo "0")
    done_count=$(grep -c '"status": "done"' "$TASKS_FILE" 2>/dev/null || echo "0")
    echo "  pending: $pending | in_progress: $in_progress | done: $done_count"
    echo "========================================="

    agent=$(resolve_agent) || {
        echo "Не найден поддерживаемый агент. Установите 'claude' или 'codex', либо задайте RALPH_AGENT." >&2
        exit 1
    }

    prompt=$(cat <<'EOF'
Прочитай CLAUDE.md, tasks.json и progress.md.

## Твоя задача
1. Найди задачу с наивысшим приоритетом (critical > high > medium > low).
   - Если есть задача со статусом in_progress — продолжи именно её.
   - Иначе возьми pending задачу, у которой ВСЕ dependencies имеют статус done.
   - Работай ТОЛЬКО над ОДНОЙ задачей.

2. Перед началом поставь статус задачи в in_progress.

3. Выполни задачу:
   - Пиши код согласно acceptance_criteria задачи.
   - Следуй конвенциям из CLAUDE.md.
   - Backend: Kotlin + Spring Boot + jOOQ. Проверяй: cd backend && ./gradlew build
   - Frontend: React + TypeScript + telegram-ui. Проверяй: cd frontend && npm run build
   - Если задача infrastructure — создавай конфиги, Dockerfile, docker-compose и т.д.

4. Проверь качество:
   - Пройди ВСЕ test_steps из задачи.
   - Убедись что билд проходит без ошибок.
   - Проверь что не сломал существующий код.

5. После завершения:
   - Поставь статус задачи в done.
   - Запиши что сделано в progress.md (дата, task id, краткое описание).
   - Сделай git commit с сообщением: feat(TASK-XXX): краткое описание

6. ЗАПРЕЩЕНО:
   - Удалять или редактировать описание задач — только менять status.
   - Трогать код, не связанный с текущей задачей.
   - Пропускать test_steps.

Если задача полностью выполнена и все тесты пройдены, выведи <promise>COMPLETE</promise>.
Если задача не может быть выполнена (блокер), выведи <promise>BLOCKED</promise> и объясни причину.
EOF
)

    result=$(run_agent "$agent" "$prompt")

    echo "$result"

    if [[ "$result" == *"<promise>COMPLETE</promise>"* ]]; then
        echo ""
        echo "✅ TASK выполнен!"
        remaining=$(grep -c '"status": "pending"' "$TASKS_FILE" 2>/dev/null || echo "0")
        if [ "$remaining" -eq 0 ]; then
            echo "🎉 Все задачи выполнены!"
            say -v Milena "Хозяин, я всё сделал!" 2>/dev/null || true
            exit 0
        fi
        echo "Осталось задач: $remaining. Продолжаю..."
        say -v Milena "Задача готова. Продолжаю работу." 2>/dev/null || true
    elif [[ "$result" == *"<promise>BLOCKED</promise>"* ]]; then
        echo ""
        echo "⚠️  TASK заблокирован! Смотри лог выше."
        echo "Пропускаю и пробую следующую задачу..."
        say -v Milena "Задача заблокирована. Пробую следующую." 2>/dev/null || true
    fi

    ((iteration++))
done

echo ""
echo "🏁 Все задачи выполнены! Итераций: $((iteration-1))"
say -v Milena "Хозяин, я всё сделал!" 2>/dev/null || true
