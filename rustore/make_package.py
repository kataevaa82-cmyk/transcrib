"""Checks the RuStore listing texts, renders the privacy policy and zips the package (everything except the APK).

    python rustore/make_package.py [--email you@example.com]

--email replaces the <УКАЖИТЕ E-MAIL> placeholder in the texts and the policy.
Graphics are made separately by make_assets.py.
"""
import html
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
NAME = "Transcrib-RuStore-1.0.0"
PKG = os.path.join(ROOT, NAME)
TEXTS = os.path.join(PKG, "01_texts")
POLICY = os.path.join(PKG, "06_privacy_policy")
EMAIL_PLACEHOLDER = "<УКАЖИТЕ E-MAIL>"

# RuStore console limits (characters).
LIMITS = {
    "01_name.txt": 30,
    "02_short_description.txt": 80,
    "03_full_description.txt": 4000,
    "04_whats_new.txt": 500,
}
FAQ_MAX_PAIRS, FAQ_Q, FAQ_A = 10, 120, 500
TAGS_MAX = 5


def read(path):
    with open(path, encoding="utf-8-sig") as f:
        return f.read().replace("\r\n", "\n")


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def field(name):
    return read(os.path.join(TEXTS, name)).strip()


def check_texts():
    ok = True
    for name, limit in LIMITS.items():
        n = len(field(name))
        flag = "OK " if n <= limit else "ПРЕВЫШЕН"
        ok &= n <= limit
        print(f"  {flag} {name}: {n} / {limit}")
    full = field("03_full_description.txt")
    print(f"      первые 2000 символов описания заканчиваются на: «…{full[1960:2000]}»")

    pairs = re.findall(r"^В: (.+)\nО: (.+)$", read(os.path.join(TEXTS, "07_faq.txt")), re.M)
    bad = [(q, len(q), len(a)) for q, a in pairs if len(q) > FAQ_Q or len(a) > FAQ_A]
    ok &= len(pairs) <= FAQ_MAX_PAIRS and not bad
    longest = max(len(a) for _, a in pairs)
    print(f"  {'OK ' if not bad and len(pairs) <= FAQ_MAX_PAIRS else 'ПРЕВЫШЕН'} 07_faq.txt: "
          f"{len(pairs)} пар, самый длинный ответ {longest} / {FAQ_A}")

    tags = re.findall(r"^\d\. (.+)$", read(os.path.join(TEXTS, "05_tags_category_age.txt")), re.M)
    ok &= len(tags) <= TAGS_MAX
    print(f"  {'OK ' if len(tags) <= TAGS_MAX else 'ПРЕВЫШЕН'} теги: {len(tags)} / {TAGS_MAX}")
    return ok


def inline(s):
    s = html.escape(s, quote=False)
    return re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)


def md_to_html(md):
    out, in_list = [], False
    for line in md.split("\n"):
        if line.startswith("- "):
            if not in_list:
                out.append("<ul>")
                in_list = True
            out.append(f"  <li>{inline(line[2:])}</li>")
            continue
        if in_list:
            out.append("</ul>")
            in_list = False
        if line.startswith("# "):
            out.append(f"<h1>{inline(line[2:])}</h1>")
        elif line.startswith("## "):
            out.append(f"<h2>{inline(line[3:])}</h2>")
        elif line.strip():
            out.append(f"<p>{inline(line)}</p>")
    if in_list:
        out.append("</ul>")
    return "\n".join(out)


PAGE = """<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Политика конфиденциальности — Транскрибатор</title>
<style>
  :root {{ --bg: #F4F5F7; --card: #FFFFFF; --text: #15181E; --muted: #5A6170; --accent: #E4432A; }}
  @media (prefers-color-scheme: dark) {{
    :root {{ --bg: #0F1115; --card: #171A21; --text: #E8EAEE; --muted: #9AA1AE; --accent: #FF8A6B; }}
  }}
  body {{ margin: 0; background: var(--bg); color: var(--text);
         font: 16px/1.6 -apple-system, "Segoe UI", Roboto, Arial, sans-serif; }}
  main {{ max-width: 760px; margin: 0 auto; padding: 32px 16px 48px; }}
  article {{ background: var(--card); border-radius: 20px; padding: 28px 24px; }}
  h1 {{ font-size: 26px; line-height: 1.25; margin: 0 0 8px; }}
  h1 + p {{ color: var(--muted); margin-top: 0; }}
  h2 {{ font-size: 19px; margin: 28px 0 8px; color: var(--accent); }}
  ul {{ padding-left: 22px; }}
  li {{ margin: 6px 0; }}
  p, li {{ overflow-wrap: anywhere; }}
</style>
</head>
<body>
<main><article>
{body}
</article></main>
</body>
</html>
"""


def md_to_txt(md):
    lines = []
    for line in md.split("\n"):
        line = line.replace("**", "")
        if line.startswith("# "):
            line = line[2:].upper()
        elif line.startswith("## "):
            line = line[3:].upper()
        elif line.startswith("- "):
            line = "• " + line[2:]
        lines.append(line)
    return "\n".join(lines)


def apply_email(email):
    for folder in (TEXTS, POLICY):
        for name in os.listdir(folder):
            if name.endswith((".txt", ".md")):
                p = os.path.join(folder, name)
                s = read(p)
                if EMAIL_PLACEHOLDER in s:
                    write(p, s.replace(EMAIL_PLACEHOLDER, email))
                    print(f"  e-mail подставлен: {name}")


def render_policy():
    md = read(os.path.join(POLICY, "privacy-policy.md"))
    write(os.path.join(POLICY, "privacy-policy.html"), PAGE.format(body=md_to_html(md)))
    write(os.path.join(POLICY, "privacy-policy.txt"), md_to_txt(md))


def build_zip():
    dst = os.path.join(ROOT, NAME + ".zip")
    if os.path.exists(dst):
        os.remove(dst)
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for dirpath, _, files in os.walk(PKG):
            for name in sorted(files):
                if name.lower().endswith((".apk", ".aab", ".py", ".pyc")):
                    continue
                path = os.path.join(dirpath, name)
                arc = os.path.join(NAME, os.path.relpath(path, PKG)).replace(os.sep, "/")
                if not arc.isascii():
                    sys.exit(f"не-ASCII имя в архиве (Проводник Windows его исказит): {arc}")
                z.write(path, arc)
    return dst


def main():
    args = sys.argv[1:]
    if "--email" in args:
        apply_email(args[args.index("--email") + 1])
    print("Проверка лимитов RuStore:")
    if not check_texts():
        sys.exit("Есть превышения лимитов — архив не собран.")
    render_policy()
    dst = build_zip()
    with zipfile.ZipFile(dst) as z:
        names = z.namelist()
    print(f"\nАрхив: {dst} ({os.path.getsize(dst) / 1024 / 1024:.1f} МБ, файлов: {len(names)})")
    for n in names:
        print("  ", n)
    # README.txt mentions the placeholder on purpose (as a to-do item).
    left = [n for n in names if n.endswith((".txt", ".md")) and not n.endswith("README.txt")
            and EMAIL_PLACEHOLDER in zipfile.ZipFile(dst).read(n).decode("utf-8")]
    if left:
        print(f"\nВНИМАНИЕ: не указан e-mail ({EMAIL_PLACEHOLDER}) в: {', '.join(left)}")


if __name__ == "__main__":
    main()
