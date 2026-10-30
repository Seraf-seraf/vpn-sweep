package cli

import (
	"github.com/Seraf-seraf/vpn-sweep/internal/search"
	"io"
	"log"
	"time"
)

func progressLog(w io.Writer) (*log.Logger, func(search.Progress)) {
	l := log.New(w, "", log.LstdFlags)
	var skipped uint64
	var lastSkip time.Time
	flush := func() {
		if skipped > 0 {
			l.Printf("Перебор: пропущено несовместимых или повторных вариантов: %d", skipped)
			skipped = 0
		}
	}
	return l, func(p search.Progress) {
		if p.Event == "candidate_skipped" {
			skipped++
			if time.Since(lastSkip) >= time.Second {
				flush()
				lastSkip = time.Now()
			}
			return
		}
		flush()
		switch p.Event {
		case "saved_result_skipped":
			l.Print("Рабочий вариант уже сохранён; поиск следующего")
		case "attempt_started":
			l.Printf("Попытка %d: конфигурация %d", p.Attempt, p.Profile)
		case "core_starting":
			l.Printf("Попытка %d, раунд %d: запуск Xray", p.Attempt, p.Round)
		case "core_ready":
			l.Printf("Попытка %d, раунд %d: Xray готов", p.Attempt, p.Round)
		case "core_failed":
			l.Printf("Попытка %d, раунд %d: ошибка Xray — %s", p.Attempt, p.Round, progressOutcome(p.Outcome))
		case "core_stopping":
			l.Printf("Попытка %d, раунд %d: остановка Xray", p.Attempt, p.Round)
		case "core_stopped":
			l.Printf("Попытка %d, раунд %d: остановка Xray — %s", p.Attempt, p.Round, progressOutcome(p.Outcome))
		case "probe_started":
			l.Printf("Попытка %d, раунд %d: проверка %d началась", p.Attempt, p.Round, p.Probe)
		case "media_started":
			l.Printf("Попытка %d, раунд %d: проверка доставки видео началась", p.Attempt, p.Round)
		case "probe_finished":
			l.Printf("Попытка %d, раунд %d, проверка %d: %s; %d байт, %d мс", p.Attempt, p.Round, p.Probe, progressOutcome(p.Outcome), p.Bytes, p.DurationMS)
		case "attempt_finished":
			l.Printf("Попытка %d завершена: %s", p.Attempt, progressOutcome(p.Outcome))
		case "budget_reached":
			l.Printf("Остановка подбора: %s", progressOutcome(p.Outcome))
		}
	}
}

func progressOutcome(outcome string) string {
	switch outcome {
	case "pass", "success":
		return "успех"
	case "failed":
		return "проверки не пройдены"
	case "partial":
		return "проверки пройдены частично"
	case "inconclusive":
		return "ресурс не дал однозначного результата"
	case "interrupted":
		return "прервано"
	case "paused_budget":
		return "достигнут лимит; поиск приостановлен"
	case "session_budget":
		return "достигнут лимит времени поиска"
	case "attempt_budget":
		return "достигнут лимит числа попыток"
	case "payload_budget":
		return "достигнут лимит полезных данных"
	case "journal_budget":
		return "достигнут лимит размера журнала"
	case "space_exhausted":
		return "варианты перебраны; рабочее соединение не найдено"
	case "capability_blocked":
		return "проверка недоступна; кандидат сохранён для повторения"
	case "timeout", "attempt_timeout":
		return "истекло время ожидания"
	case "request_failed", "transfer_failed", "tls_failure", "tls_unverified", "site_consent", "site_forbidden", "site_rate_limited", "site_restricted", "site_challenge", "unexpected_http_status", "unexpected_content", "unexpected_mime", "body_too_small", "body_too_large", "wrong_body_size", "hash_mismatch", "redirect_rejected", "unsupported", "core_start_timeout", "core_config_rejected", "core_start_failed", "core_exited_before_ready", "core_exited", "core_signaled", "core_signal_9_cause_unknown", "core_identity_changed", "core_signal_failed", "core_kill_failed", "core_wait_timeout", "reference_unverified", "resolver_unavailable", "resolver_blocked", "resolver_auth_or_challenge", "resolver_token_required", "resolver_network_failed", "resolver_identity_changed", "resolver_invalid_output", "resolver_output_limit", "resolver_unsupported", "endpoint_status_unsupported", "media_mime_unsupported", "container_unsupported_or_invalid", "range_mismatch", "udp_content_mismatch", "udp_dns_invalid_response", "udp_dns_random_failed", "udp_reply_source_mismatch", "udp_reply_too_large", "udp_timeout_or_read_failed":
		return outcome
	default:
		return "ошибка"
	}
}
