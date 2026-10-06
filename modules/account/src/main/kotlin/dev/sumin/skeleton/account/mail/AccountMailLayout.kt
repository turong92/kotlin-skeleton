package dev.sumin.skeleton.account.mail

import dev.sumin.skeleton.account.AccountProperties

/** 버튼 하나 — [label] 이 글자, [url] 이 가는 곳 */
data class MailButton(val label: String, val url: String)

/**
 * 메일 한 통의 내용(구조) — **값은 날것이다(이스케이프 전)**. 틀([AccountMailLayout])이 자기 출력에 맞게 이스케이프한다.
 * [code] 가 있으면 큰 코드 블록, [button] 이 있으면 버튼 + 같은 주소의 글자(링크가 안 눌릴 때).
 */
data class MailPage(
    val lang: String,
    val heading: String,
    val preheader: String,
    val paragraphs: List<String>,
    val code: String? = null,
    val button: MailButton? = null,
    /** 코드 아래의 강조 한 줄 (예: 이 코드를 누구에게도 알려주지 마세요) */
    val warning: String? = null,
)

/** HTML 틀 — 앱이 `AccountMailLayout` 빈을 두면 내장 틀([DefaultAccountMailLayout]) 대신 쓴다 */
fun interface AccountMailLayout {
    fun wrap(page: MailPage): String
}

/**
 * 내장 틀: 표 기반 · 인라인 CSS · 폭 560px · 시스템 글꼴 · 이미지 없이도 읽힌다 · 어두운 배경에서도 읽히는 색 (`color-scheme`).
 * 모든 값은 여기서 이스케이프한다. 로고는 https 주소만, 색은 16진수만, 문의 주소는 이메일 모양만 받는다 — 아니면 조용히 버린다.
 */
class DefaultAccountMailLayout(private val brand: AccountProperties.Mail.Brand) : AccountMailLayout {
    private val name = oneLine(brand.serviceName)
    private val accent = if (HEX.matches(brand.accentColor.trim())) brand.accentColor.trim() else DEFAULT_ACCENT
    private val logo = brand.logoUrl.trim().takeIf { it.startsWith("https://") && it.none { c -> c.isWhitespace() || c == '"' || c == '<' || c == '>' } }
    private val support = brand.supportAddress.trim().takeIf { EMAIL.matches(it) }
    private val footerLine = oneLine(brand.footer)

    override fun wrap(page: MailPage): String {
        val ko = page.lang == "ko"
        val b = StringBuilder(2048)
        b.append("<!doctype html><html lang=\"").append(if (ko) "ko" else "en").append("\"><head><meta charset=\"utf-8\">")
        b.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        b.append("<meta name=\"color-scheme\" content=\"light dark\"><meta name=\"supported-color-schemes\" content=\"light dark\">")
        b.append("<title>").append(esc(page.heading)).append("</title></head>")
        b.append("<body style=\"margin:0;padding:0;background:#f4f5f7;color:#1f2933;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Apple SD Gothic Neo','Malgun Gothic',Roboto,Helvetica,Arial,sans-serif\">")
        b.append("<div style=\"display:none;max-height:0;overflow:hidden;opacity:0;font-size:1px;line-height:1px;color:#f4f5f7\">").append(esc(page.preheader)).append("</div>")
        b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"background:#f4f5f7\"><tr><td align=\"center\" style=\"padding:24px 12px\">")
        b.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"max-width:560px;background:#ffffff;border-radius:8px;border-top:4px solid ").append(accent).append("\">")
        // header: logo or the service name as text
        if (logo != null || name.isNotEmpty()) {
            b.append("<tr><td style=\"padding:24px 32px 0 32px;font-size:16px;font-weight:600;color:#1f2933\">")
            if (logo != null) b.append("<img src=\"").append(esc(logo)).append("\" alt=\"").append(esc(name)).append("\" height=\"32\" style=\"display:block;height:32px;border:0\">")
            else b.append(esc(name))
            b.append("</td></tr>")
        }
        b.append("<tr><td style=\"padding:24px 32px 8px 32px\"><h1 style=\"margin:0 0 16px 0;font-size:20px;line-height:1.35;color:#1f2933\">").append(esc(page.heading)).append("</h1>")
        page.paragraphs.forEach { b.append("<p style=\"margin:0 0 14px 0;font-size:15px;line-height:1.6;color:#3e4c59\">").append(esc(it)).append("</p>") }
        page.code?.let {
            b.append("<div style=\"margin:20px 0;padding:16px;text-align:center;background:#eef2ff;border-radius:6px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:32px;font-weight:700;letter-spacing:8px;color:#111827\">").append(esc(it)).append("</div>")
        }
        page.warning?.let { b.append("<p style=\"margin:0 0 14px 0;font-size:14px;line-height:1.6;font-weight:700;color:#b42318\">").append(esc(it)).append("</p>") }
        page.button?.let {
            b.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:20px 0\"><tr><td bgcolor=\"").append(accent).append("\" style=\"border-radius:6px;background:").append(accent).append("\">")
            b.append("<a href=\"").append(esc(it.url)).append("\" style=\"display:inline-block;padding:12px 28px;font-size:16px;font-weight:600;color:#ffffff;text-decoration:none\">").append(esc(it.label)).append("</a></td></tr></table>")
            b.append("<p style=\"margin:0 0 14px 0;font-size:12px;line-height:1.5;color:#6b7280\">").append(if (ko) "버튼이 안 눌리면 이 주소를 브라우저에 붙여 넣으세요." else "If the button does not work, paste this address into your browser.")
            b.append("<br><span style=\"word-break:break-all\">").append(esc(it.url)).append("</span></p>")
        }
        b.append("</td></tr>")
        b.append("<tr><td style=\"padding:16px 32px 28px 32px;border-top:1px solid #e5e7eb;font-size:12px;line-height:1.6;color:#6b7280\">")
        b.append(if (ko) "요청하지 않았다면 무시하세요." else "If you did not request this, you can ignore this email.")
        if (name.isNotEmpty()) b.append("<br>").append(esc(name))
        if (footerLine.isNotEmpty()) b.append("<br>").append(esc(footerLine))
        if (support != null) b.append("<br>").append(if (ko) "문의: " else "Contact: ").append("<a href=\"mailto:").append(esc(support)).append("\" style=\"color:#6b7280\">").append(esc(support)).append("</a>")
        b.append("</td></tr></table></td></tr></table></body></html>")
        return b.toString()
    }

    private companion object {
        const val DEFAULT_ACCENT = "#2563eb"
        val HEX = Regex("#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})")
        val EMAIL = Regex("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        fun oneLine(s: String) = s.replace(Regex("[\\r\\n\\u0000-\\u001f\\u2028\\u2029]+"), " ").trim()
        fun esc(s: String) = buildString(s.length + 16) {
            for (c in s) when (c) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
