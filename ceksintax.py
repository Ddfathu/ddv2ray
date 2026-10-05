#!/usr/bin/env python3
import os
import re
import sys

CURRENT_DIR = os.getcwd()

def check_brackets_balance(lines):
    bracket_map = {')': '(', '}': '{', ']': '['}
    stack = []
    issues = []
    in_block_comment = False
    
    for idx, raw_line in enumerate(lines, start=1):
        i = 0
        cleaned = []
        in_string = False
        
        while i < len(raw_line):
            ch = raw_line[i]
            if in_block_comment:
                if raw_line[i:i+2] == '*/':
                    in_block_comment = False
                    i += 2
                    continue
                i += 1
                continue
            if not in_string and raw_line[i:i+2] == '/*':
                in_block_comment = True
                i += 2
                continue
            if not in_string and raw_line[i:i+2] == '//':
                break
            if ch == '"' and (i == 0 or raw_line[i-1] != '\\'):
                in_string = not in_string
                i += 1
                continue
            if not in_string and not in_block_comment:
                cleaned.append(ch)
            i += 1
            
        clean_text = "".join(cleaned)
        for ch in clean_text:
            if ch in "({[":
                stack.append((ch, idx))
            elif ch in ")}]":
                if not stack:
                    issues.append((idx, f"Kurung tutup berlebih '{ch}' tanpa kurung buka"))
                else:
                    top_ch, top_idx = stack.pop()
                    if top_ch != bracket_map[ch]:
                        issues.append((idx, f"Kurung tidak cocok: buka '{top_ch}' ditutup '{ch}'"))
                        
    if stack:
        for ch, line_no in stack[-3:]:
            issues.append((line_no, f"Kurung buka '{ch}' belum ditutup"))
            
    return issues

def audit_file(filepath):
    issues = []
    try:
        with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
            lines = f.readlines()
    except Exception as e:
        return [{"line": 0, "sev": "CRITICAL", "type": "FILE_READ_ERROR", "msg": f"Gagal membaca file: {e}", "code": ""}]
        
    bracket_errors = check_brackets_balance(lines)
    for l_no, err_msg in bracket_errors:
        issues.append({
            "line": l_no,
            "sev": "CRITICAL",
            "type": "BRACKET_MISMATCH",
            "msg": err_msg,
            "code": lines[l_no-1].strip() if 0 < l_no <= len(lines) else ""
        })

    for idx, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        if idx == 1 and line.startswith("Package "):
            issues.append({"line": idx, "sev": "CRITICAL", "type": "TYPO_PACKAGE", "msg": "Kata 'Package' harus huruf kecil", "code": line})
        if re.search(r'\bput\s*\(\s*[a-zA-Z0-9_]+\s*,\s*[a-zA-Z0-9_]+\s*\)', line):
            issues.append({"line": idx, "sev": "CRITICAL", "type": "JSONARRAY_PUT_MULTI_ARG", "msg": "JSONArray.put() tidak menerima 2 argumen sekaligus", "code": line})
        if re.search(r'(?<!\bapplicationContext\.)(?<!\bthis\.)\bpackageName\b', line) and not line.startswith("package ") and not line.startswith("//") and not line.startswith("*"):
            issues.append({"line": idx, "sev": "CRITICAL", "type": "KPROPERTY_TYPE_MISMATCH", "msg": "Bare 'packageName' memicu KProperty0 mismatch! Gunakan 'applicationContext.packageName'", "code": line})

    return issues

def main():
    target = sys.argv[1] if len(sys.argv) > 1 else CURRENT_DIR
    print("=" * 75)
    print("🚀 UNIVERSAL KOTLIN AUDITOR V7.5 (DYNAMIC DIR)")
    print(f"📁 Target Folder Scan : {target}")
    print("=" * 75)

    total_files = 0
    bad_files = 0
    total_issues = 0

    for root, _, files in os.walk(target):
        for file in files:
            if file.endswith(".kt") or file.endswith(".kts"):
                total_files += 1
                filepath = os.path.join(root, file)
                relpath = os.path.relpath(filepath, target)
                issues = audit_file(filepath)

                if issues:
                    bad_files += 1
                    total_issues += len(issues)
                    print(f"📄 {relpath} ➔ {len(issues)} MASALAH DITEMUKAN:")
                    for iss in issues:
                        print(f"   🔴 [Baris {iss['line']}] [{iss['type']}]")
                        print(f"      Penyebab : {iss['msg']}")
                        if iss['code']:
                            print(f"      Potongan : {iss['code'][:110]}")
                    print("-" * 75)

    print("\n" + "=" * 75)
    print("📊 KESIMPULAN AUDIT:")
    print(f"   • Total file Kotlin diperiksa : {total_files}")
    print(f"   • File terdapat error         : {bad_files}")
    print(f"   • Total isu error             : {total_issues}")
    if total_issues == 0:
        print("   🎉 100% AMAN! Siap jalankan: ./gradlew assembleDebug")
    print("=" * 75)

if __name__ == "__main__":
    main()
