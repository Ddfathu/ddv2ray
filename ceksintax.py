#!/usr/bin/env python3
import os
import re
import sys

TARGET_DIR = "/sdcard/github_projects/ddv2ray"

def extract_api_methods(root_dir):
    defined_methods = set()
    api_client_path = None
    
    for r, _, files in os.walk(root_dir):
        for f in files:
            if f == "ApiClient.kt":
                api_client_path = os.path.join(r, f)
                break
        if api_client_path:
            break
            
    if not api_client_path:
        return defined_methods, None
        
    try:
        with open(api_client_path, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
            matches = re.findall(r'suspend\s+fun\s+([a-zA-Z0-9_]+)\s*\(', content)
            defined_methods = set(matches)
    except Exception as e:
        print(f"Error membaca ApiClient.kt: {e}")
        
    return defined_methods, api_client_path

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
                        issues.append((idx, f"Kurung tidak cocok: buka '{top_ch}' (baris {top_idx}) ditutup dengan '{ch}' (baris {idx})"))
                        
    if stack:
        for ch, line_no in stack[-3:]:
            issues.append((line_no, f"Kurung buka '{ch}' pada baris ini belum ditutup sampai akhir file"))
            
    return issues

def audit_file(filepath, defined_api_methods):
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
        
    full_file_text = "".join(lines)

    # 1. DETEKSI COMPOSABLE DI DALAM .forEach { ... }
    forEach_matches = re.finditer(r'\.[a-zA-Z0-9_]+\.forEach\s*\{[^}]*?\b(Text|Surface|Button|Card|Row|Column|Box|OutlinedButton|IconButton)\s*\(', full_file_text)
    for m in forEach_matches:
        line_num = full_file_text[:m.start()].count('\n') + 1
        issues.append({
            "line": line_num,
            "sev": "CRITICAL",
            "type": "COMPOSABLE_IN_COLLECTION_FOREACH",
            "msg": "Pemanggilan @Composable (Text/Surface/Button/dll) di dalam Collection .forEach { } dilarang! Wajib gunakan for loop biasa: for (item in list) { ... }",
            "code": lines[line_num-1].strip() if 0 < line_num <= len(lines) else ""
        })

    # 2. KHUSUS PAGES SCREEN: DETEKSI LOADPROJECTS KELUPAAN
    if "PagesScreen.kt" in filepath:
        if "loadProjects()" in full_file_text and "fun loadProjects()" not in full_file_text:
            issues.append({
                "line": 1,
                "sev": "CRITICAL",
                "type": "MISSING_FUNCTION_DECLARATION",
                "msg": "Fungsi 'loadProjects()' dipanggil di file ini tetapi pendeklarasian 'fun loadProjects()' belum ada!",
                "code": "loadProjects()"
            })

    # === DETEKSI EXTENSION FUNCTION POSITION IN FILE ===
    ext_fn_matches = re.finditer(r'fun\s+JsonObject\.[a-zA-Z0-9_]+\s*\(', full_file_text)
    composable_pos = full_file_text.find("@Composable")
    for m in ext_fn_matches:
        if composable_pos != -1 and m.start() > composable_pos:
            line_num = full_file_text[:m.start()].count('\n') + 1
            issues.append({
                "line": line_num,
                "sev": "CRITICAL",
                "type": "MISPLACED_EXTENSION_FUNCTION",
                "msg": "Fungsi ekstensi JsonObject diletakkan di bawah @Composable! Pindahkan ke bagian paling atas file (di bawah import).",
                "code": lines[line_num-1].strip() if 0 < line_num <= len(lines) else ""
            })

    for idx, raw_line in enumerate(lines, start=1):
        line = raw_line.strip()
        
        # 3. Typo Package kapital
        if idx == 1 and line.startswith("Package "):
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "TYPO_PACKAGE",
                "msg": "Kata 'Package' diawali huruf kapital, wajib 'package' huruf kecil",
                "code": line
            })

        # 4. Anotasi @JvmSuppressWildcards salah tempat di parameter
        if re.search(r'@[a-zA-Z0-9_]+\s+@JvmSuppressWildcards\s+[a-zA-Z0-9_]+\s*:', raw_line) or re.search(r'@JvmSuppressWildcards\s+[a-zA-Z0-9_]+\s*:', raw_line):
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "INVALID_ANNOTATION_TARGET",
                "msg": "@JvmSuppressWildcards salah tempat! Jangan ditaruh di depan nama parameter. Pasang satu kali di atas 'interface WorkerApi'!",
                "code": line
            })

        # 5. Deteksi literal '\n' mentah di luar string
        line_outside_strings = re.sub(r'".*?"', '""', raw_line)
        if "\\n" in line_outside_strings:
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "RAW_NEWLINE_TOKEN_ERROR",
                "msg": "Terdapat teks literal '\\n' mentah di luar tanda kutip yang menggabungkan 2 perintah tanpa enter (memicu 'Unexpected tokens')",
                "code": line
            })

        # 6. Escape dollar template: \${
        if "\\${" in line:
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "ESCAPE_DOLLAR_BUG",
                "msg": "Terdapat '\\${' yang memicu syntax error 'Expecting an element / Expression cannot be invoked'",
                "code": line
            })
            
        # 7. Kutip ganda di dalam interpolation Elvis
        if re.search(r'\$\{[^}]*?\?:.*?"[^}]*\}', line):
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "NESTED_QUOTE_INTERPOLATION",
                "msg": "Kutipan ganda di dalam ekspresi ${ ... ?: \"...\" } memicu 'Unresolved reference'",
                "code": line
            })
            
        # 8. Akses Langsung JsonElement tanpa asJsonObject
        if re.search(r'\b(result|body\(\)|resBody|resCatch\.body\(\)\?\.result)\??\.(get\s*\(|getAsJsonArray\s*\(["\']|getAsJsonObject\s*\(["\'])', line):
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "JSONELEMENT_METHOD_MISMATCH",
                "msg": "Pemanggilan .get() atau .getAsJsonArray(key) langsung pada JsonElement tanpa .asJsonObject",
                "code": line
            })
            
        # 9. Pemanggilan ApiClient.api yang belum terdaftar
        api_calls = re.findall(r'ApiClient\.api\.([a-zA-Z0-9_]+)\s*\(', line)
        for method in api_calls:
            if defined_api_methods and method not in defined_api_methods:
                issues.append({
                    "line": idx,
                    "sev": "CRITICAL",
                    "type": "UNDEFINED_API_ENDPOINT",
                    "msg": f"Method 'ApiClient.api.{method}()' TIDAK DITEMUKAN / BELUM DIDAFTARKAN di WorkerApi ApiClient.kt!",
                    "code": line
                })

        # 10. DETEKSI TYPO METHOD GSON
        if "getJsonObject(" in line:
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "GSON_METHOD_TYPO",
                "msg": "Method 'getJsonObject()' tidak ada di library Gson! Gunakan 'getAsJsonObject()'",
                "code": line
            })

        # 11. DETEKSI KEYSET FOREACH AMBIGUITY
        if "keySet().forEach" in line or "keySet()?.forEach" in line:
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "GSON_KEYSET_AMBIGUITY",
                "msg": "Penggunaan 'keySet().forEach' memicu Overload resolution ambiguity di Kotlin. Gunakan 'entrySet().forEach { ... }'",
                "code": line
            })

        # === [PENAMBAHAN BARU 12] DETEKSI MULTI-ARGUMENT JSONARRAY.PUT ===
        if re.search(r'\bput\s*\(\s*[a-zA-Z0-9_]+\s*,\s*[a-zA-Z0-9_]+\s*\)', line):
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "JSONARRAY_PUT_MULTI_ARG",
                "msg": "JSONArray.put() tidak menerima 2 argumen sekaligus! Pisah jadi dua pemanggilan .put() secara terpisah.",
                "code": line
            })

        # === [PENAMBAHAN BARU 13] DETEKSI BARE PACKAGENAME REFERENCE BUG ===
        if re.search(r'\baddDisallowedApplication\s*\(\s*packageName\s*\)', line):
            issues.append({
                "line": idx,
                "sev": "CRITICAL",
                "type": "KPROPERTY_TYPE_MISMATCH",
                "msg": "Penggunaan bare 'packageName' di sini terbaca sebagai KProperty0<String>. Gunakan 'applicationContext.packageName' atau variabel String eksplisit!",
                "code": line
            })

    return issues

def main():
    target = sys.argv[1] if len(sys.argv) > 1 else TARGET_DIR
    print("=" * 75)
    print("🚀 ONE-CLICK FULL AUDITOR V6.4 (UPDATED): CF MANAGER & VPN ANDROID")
    print(f"📁 Target Folder : {target}")
    print("=" * 75)

    if not os.path.exists(target):
        print(f"❌ Error: Folder target tidak ditemukan: {target}")
        sys.exit(1)

    print("🔎 1. Memindai definisi method di ApiClient.kt...")
    defined_methods, api_path = extract_api_methods(target)
    if not api_path:
        print("⚠️  Peringatan: File ApiClient.kt tidak ditemukan di direktori target.")
    else:
        print(f"✅ Ditemukan {len(defined_methods)} method API terdaftar di {os.path.basename(api_path)}")

    print("\n🔎 2. Memeriksa sintaks (Composable, Anotasi, Bracket, Escape, API, Gson, Multi-Arg, Type Mismatch)...\n")

    total_files = 0
    bad_files = 0
    total_issues = 0

    for root, _, files in os.walk(target):
        for file in files:
            if file.endswith(".kt") or file.endswith(".kts"):
                total_files += 1
                filepath = os.path.join(root, file)
                relpath = os.path.relpath(filepath, target)
                issues = audit_file(filepath, defined_methods)

                if issues:
                    bad_files += 1
                    total_issues += len(issues)
                    print(f"📄 {relpath} ➔ {len(issues)} MASALAH DITEMUKAN:")
                    for iss in issues:
                        sev_badge = "🔴" if iss["sev"] == "CRITICAL" else "🟡"
                        print(f"   {sev_badge} [Baris {iss['line']}] [{iss['type']}]")
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
    else:
        print(f"   ⚠️  Ditemukan {total_issues} error sintaks yang bikin build gagal.")
        print("   Silakan perbaiki baris-baris bertanda 🔴 di atas terlebih dahulu.")
    print("=" * 75)

if __name__ == "__main__":
    main()
