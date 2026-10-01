# shellcheck shell=bash
#
# check_catalog — the version catalog is the single source of truth for external versions.
# Task 15.5; Requirements 9.x, 9.8 for the allow-list.
#
# Artefacts: gradle/libs.versions.toml plus the four build files
#
#   build.gradle.kts
#   streaming-core/build.gradle.kts
#   streaming-s3-example/build.gradle.kts
#   streaming-s3-example-java/build.gradle.kts
#
# Four invariants:
#
#   1. No `extra["…"]` / `rootProject.extra[…]` version holder in any of the four build
#      files. Dependabot's Gradle parser does not evaluate Kotlin-DSL map lookups, so a
#      version parked in `extra` is a version nothing bumps (docs/log.md) — which is the
#      reason the catalog exists at all.
#   2. No inline version literal in a dependency coordinate in those four files: every
#      external dependency is declared through a `libs.` accessor, every internal one
#      through `project(...)`. Asserted from both sides — a coordinate-shaped literal is a
#      failure, and a configuration line whose argument is not a `libs.` accessor is too.
#   3. gradle/libs.versions.toml parses, and every `version.ref` in [libraries]/[plugins]
#      names an existing [versions] key. An unresolvable ref fails at configuration time;
#      a typo in one is easy to miss by eye.
#   4. The two deliberately versionless [libraries] entries are present and still
#      versionless: `aws-sdk-java-s3` (version from the BOM) and `junit-platform-launcher`
#      (constrained by junit-jupiter). Either one gaining a version is a silent split.
#
# The allow-list (Requirement 9.8)
# --------------------------------
# Five version literals deliberately stay, and they are NOT hardcoded here. They are read
# at runtime from the docs/log.md entry "Version literals that deliberately stay — the
# Requirement 9.8 allow-list", which the log itself names as the allow-list the
# verification script reads. The consequence is the point: a literal that is not recorded
# there fails this check, and an entry deleted from the log stops exempting anything. The
# script's own list below carries, per entry, the fixed text that must appear in that
# section — so the two cannot drift, the same way check_pending_badges pins the pending
# badges to their log entry.
#
# Each entry with a file of its own is also asserted to still be there in the documented
# shape. That cuts both ways for the Floci Docker tag: the exemption matches only the
# composed `floci/floci:${libs.versions.flociImage.get()}` form, so hardcoding the tag
# fails as an unexempted literal *and* fails the presence assertion.
#
# Every check file is sourced into one shell, so the helpers below are defined inside the
# function, prefixed `_catalog_`, and unset again at the end.

check_catalog() {
    local catalog="gradle/libs.versions.toml"
    local log_heading='^## Version literals that deliberately stay'

    local -a catalog_build_files=(
        "build.gradle.kts"
        "streaming-core/build.gradle.kts"
        "streaming-s3-example/build.gradle.kts"
        "streaming-s3-example-java/build.gradle.kts"
    )

    # key | files (';'-separated, '-' when the artefact is the catalog itself)
    #     | line regex ('-' when presence is asserted by the versionless check)
    #     | text that must appear in the docs/log.md section
    #     | description
    # No '|' inside a field. Regexes are awk EREs and use bracket expressions rather than
    # backslash escapes, because awk -v processes escapes in the assignment.
    local -a catalog_allowlist=(
        'foojay-resolver|settings.gradle.kts|foojay-resolver-convention.*version[[:space:]]+"|foojay-resolver-convention|the foojay-resolver plugin version in the settings script, which is evaluated before the catalog exists'
        'floci-image|streaming-s3-example/build.gradle.kts;streaming-s3-example-java/build.gradle.kts|floci/floci:[$][{]libs[.]versions[.]flociImage[.]get[(][)][}]|floci/floci:${libs.versions.flociImage.get()}|the composed Floci Docker image tag, whose tag comes from [versions] flociImage while the image name is not a Maven coordinate'
        'release-version|streaming-core/build.gradle.kts|^[[:space:]]*version[[:space:]]*=.*releaseVersion|providers.gradleProperty("releaseVersion")|:streaming-core its own published version, taken from -PreleaseVersion so the git tag stays the single source of truth'
        'junit-platform-launcher|-|-|junit-platform-launcher|the versionless junit-platform-launcher catalog entry, constrained by junit-jupiter'
        'aws-sdk-java-s3|-|-|aws-sdk-java-s3|the versionless aws-sdk-java-s3 catalog entry, whose version comes from the AWS SDK for Java BOM'
    )

    # Candidate patterns for invariant 2, in the order they are scanned.
    #   coordinate  "group:artifact:1.2.3"          — the classic inline version
    #   image       "repo/name:1.2.3"               — a hardcoded container tag
    #   plugin      id("…") version "1.2.3"         — the plugin-DSL form
    #   assignment  version = "1.2.3" / toolVersion — a version set in the build script
    local catalog_coord_re='"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[0-9][A-Za-z0-9_.+-]*"'
    local catalog_image_re='"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+:[0-9][A-Za-z0-9_.+-]*"'
    local catalog_plugin_re='version[[:space:]]+"[^"]+"'
    local catalog_assign_re='^[[:space:]]*(version|toolVersion)[[:space:]]*=.*"[0-9]+[.][0-9]+'
    local catalog_extra_re='extra[[:space:]]*[[]'
    local catalog_dep_re='^[[:space:]]*(implementation|api|compileOnly|compileOnlyApi|runtimeOnly|testImplementation|testCompileOnly|testRuntimeOnly|annotationProcessor|kapt|ksp)[[:space:]]*[(]'

    # Keys whose log entry was found, as " key key " for substring testing (no associative
    # arrays: this has to run on whatever bash 4 the contributor has).
    local catalog_documented=" "

    # ---------------------------------------------------------------------------
    # Helpers
    # ---------------------------------------------------------------------------

    # _catalog_scan <file> <awk ERE> — "<line number><TAB><trimmed line>" per match.
    # Whole-line comments are skipped, so prose about a version is never mistaken for one.
    _catalog_scan() {
        awk -v pattern="$2" '
            {
                line = $0
                gsub(/\t/, " ", line)
                trimmed = line
                sub(/^[[:space:]]+/, "", trimmed)
                sub(/[[:space:]]+$/, "", trimmed)
            }
            trimmed ~ /^(\/\/|\/\*|\*)/ { next }
            trimmed ~ pattern { printf "%d\t%s\n", FNR, trimmed }
        ' "$1"
    }

    # _catalog_match <line> <awk ERE> — the matched substring, for naming the literal.
    _catalog_match() {
        printf '%s\n' "$1" | awk -v pattern="$2" '
            match($0, pattern) { print substr($0, RSTART, RLENGTH); exit }
        '
    }

    # _catalog_line_matches <line> <awk ERE>
    _catalog_line_matches() {
        printf '%s\n' "$1" | awk -v pattern="$2" '
            $0 ~ pattern { found = 1 }
            END { exit found ? 0 : 1 }
        '
    }

    # _catalog_allowed <file> <line> — prints the allow-list key covering this line, or
    # returns non-zero. Only entries whose log text was found are consulted, which is what
    # makes docs/log.md the source of truth rather than a copy of it.
    _catalog_allowed() {
        local file=$1 line=$2 entry key files line_re marker desc f
        for entry in "${catalog_allowlist[@]}"; do
            IFS='|' read -r key files line_re marker desc <<<"$entry"
            [[ "$line_re" != "-" ]] || continue
            [[ "$catalog_documented" == *" ${key} "* ]] || continue
            for f in ${files//;/ }; do
                [[ "$f" == "$file" ]] || continue
                if _catalog_line_matches "$line" "$line_re"; then
                    printf '%s\n' "$key"
                    return 0
                fi
            done
        done
        return 1
    }

    # _catalog_toml_dump <file> — flatten the catalog to one record per declaration:
    #
    #   SECTION<TAB><line><TAB><section>
    #   ENTRY<TAB><line><TAB><section><TAB><key><TAB><value>
    #   BAD<TAB><line><TAB><section><TAB><text>
    #
    # Comments are stripped outside quoted strings, and a declaration whose inline table or
    # array spans several lines is joined before it is emitted. Anything that is neither a
    # section header nor `key = value` is a BAD record: that is what "the catalog parses"
    # means here, and it names the line rather than shrugging.
    _catalog_toml_dump() {
        awk '
            function strip_comment(s,   i, ch, inq, out) {
                inq = 0; out = ""
                for (i = 1; i <= length(s); i++) {
                    ch = substr(s, i, 1)
                    if (ch == "\"") inq = !inq
                    if (ch == "#" && !inq) break
                    out = out ch
                }
                return out
            }
            function occurrences(s, re,   tmp) { tmp = s; return gsub(re, "", tmp) }
            function balanced(s) {
                return occurrences(s, "[{]") == occurrences(s, "[}]") &&
                       occurrences(s, "[[]") == occurrences(s, "[]]")
            }
            function emit(   key, value) {
                key = buf
                sub(/[[:space:]]*=.*$/, "", key)
                value = buf
                sub(/^[^=]*=[[:space:]]*/, "", value)
                printf "ENTRY\t%d\t%s\t%s\t%s\n", bufline, section, key, value
                buf = ""
            }
            BEGIN { section = ""; buf = ""; bufline = 0 }
            {
                line = strip_comment($0)
                gsub(/\t/, " ", line)
                sub(/^[[:space:]]+/, "", line)
                sub(/[[:space:]]+$/, "", line)
            }
            buf != "" {
                buf = buf " " line
                if (balanced(buf)) emit()
                next
            }
            line == "" { next }
            line ~ /^[[]/ {
                section = line
                gsub(/[][]/, "", section)
                printf "SECTION\t%d\t%s\n", FNR, section
                next
            }
            line ~ /^[A-Za-z0-9_.-]+[[:space:]]*=/ {
                buf = line
                bufline = FNR
                if (balanced(buf)) emit()
                next
            }
            {
                printf "BAD\t%d\t%s\t%s\n", FNR, section, line
            }
            END {
                if (buf != "") printf "BAD\t%d\t%s\t%s\n", bufline, section, "unterminated declaration: " buf
            }
        ' "$1"
    }

    _catalog_body() {
        local entry key files line_re marker desc f
        local file lineno line pattern literal allowed_key
        local kind sect value

        # --- The allow-list, read from docs/log.md --------------------------------------
        local allowlist_readable="false" section="" flat=""
        if [[ ! -f "$LOG_FILE" ]]; then
            report_fail "$LOG_FILE" "file not found; the Requirement 9.8 allow-list of deliberate version literals lives there, so a documented literal cannot be told apart from an unmanaged one"
        else
            section="$(log_section "$log_heading")"
            if [[ -z "$section" ]]; then
                report_fail "$LOG_FILE" "no section matching '${log_heading}' — the Requirement 9.8 allow-list is the source of truth for which version literals may stay, and this check reads it rather than carrying its own copy"
            else
                allowlist_readable="true"
                flat="$(printf '%s\n' "$section" | tr '\n' ' ' | tr -s ' ')"
            fi
        fi

        if [[ "$allowlist_readable" == "true" ]]; then
            local undocumented=()
            for entry in "${catalog_allowlist[@]}"; do
                IFS='|' read -r key files line_re marker desc <<<"$entry"
                if printf '%s\n' "$flat" | grep -qF -- "$marker"; then
                    catalog_documented+="${key} "
                else
                    undocumented+=("$key")
                    report_fail "$LOG_FILE" "the allow-list section does not document '${key}' (${desc}); expected the text '${marker}'. Either restore the entry or drop the exemption — an exemption the log does not record must not silence this check"
                fi
            done

            # Set equality in the other direction: a sixth entry in the log is an exemption
            # this check knows nothing about, so it would be enforced as a violation.
            local numbered_count
            numbered_count="$(printf '%s\n' "$section" | grep -cE '^[[:space:]]*[0-9]+\.[[:space:]]' || true)"
            numbered_count="${numbered_count//[^0-9]/}"
            if (( numbered_count == ${#catalog_allowlist[@]} )); then
                if (( ${#undocumented[@]} == 0 )); then
                    report_pass "$LOG_FILE" "the allow-list documents all ${numbered_count} exemptions this check honours ($(printf '%s' "${catalog_documented# }" | sed -E 's/[[:space:]]+$//; s/[[:space:]]+/, /g'))"
                fi
            else
                report_fail "$LOG_FILE" "the allow-list section lists ${numbered_count} numbered exemption(s) but this check honours ${#catalog_allowlist[@]}; the two must agree, or a literal the log permits is reported as a violation (or worse, the reverse)"
            fi
        else
            report_skip "the version-literal scan" "the ${LOG_FILE} allow-list is unreadable, so a deliberate literal cannot be distinguished from an unmanaged one; fix the log entry and re-run"
        fi

        # --- Each documented exception is still there, in the documented shape ----------
        if [[ "$allowlist_readable" == "true" ]]; then
            local hits
            for entry in "${catalog_allowlist[@]}"; do
                IFS='|' read -r key files line_re marker desc <<<"$entry"
                [[ "$line_re" != "-" ]] || continue
                [[ "$catalog_documented" == *" ${key} "* ]] || continue
                for f in ${files//;/ }; do
                    if [[ ! -f "$f" ]]; then
                        report_fail "$f" "file not found, yet ${LOG_FILE} records ${desc} in it"
                        continue
                    fi
                    hits="$(_catalog_scan "$f" "$line_re")"
                    if [[ -n "$hits" ]]; then
                        report_pass "$f" "line $(printf '%s\n' "$hits" | head -n 1 | cut -f1): documented exemption '${key}' still present in the shape the log describes — ${desc}"
                    else
                        report_fail "$f" "${LOG_FILE} records ${desc} here, but no line matches it any more. Either it moved (update the log entry) or it is gone (delete the entry), so no unmanaged literal can shelter behind a stale exemption"
                    fi
                done
            done
        fi

        # --- Invariants 1 and 2, over the four build files ------------------------------
        local -a candidates=() violations=() bad_deps=() exempted=()
        local seen_lines="" arg dep_count
        for file in "${catalog_build_files[@]}"; do
            if [[ ! -f "$file" ]]; then
                report_fail "$file" "build file not found; the catalog invariants cannot be checked against it"
                continue
            fi

            # 1. extra[...] version holders.
            hits="$(_catalog_scan "$file" "$catalog_extra_re")"
            if [[ -z "$hits" ]]; then
                report_pass "$file" "no extra[…] / rootProject.extra[…] version holder — Dependabot's Gradle parser does not evaluate Kotlin-DSL map lookups, so a version held there is a version nothing bumps"
            else
                while IFS=$'\t' read -r lineno line; do
                    [[ -n "$lineno" ]] || continue
                    report_fail "$file" "line ${lineno}: version held in an extra[…] map — '${line}'. Dependabot cannot evaluate a Kotlin-DSL map lookup, so move the value to a [versions] entry in ${catalog} and reference it through a libs. accessor"
                done <<<"$hits"
            fi

            # 2a. Inline version literals.
            if [[ "$allowlist_readable" == "true" ]]; then
                candidates=()
                seen_lines=""
                for pattern in "$catalog_coord_re" "$catalog_image_re" "$catalog_plugin_re" "$catalog_assign_re"; do
                    while IFS=$'\t' read -r lineno line; do
                        [[ -n "$lineno" ]] || continue
                        [[ "$seen_lines" != *" ${lineno} "* ]] || continue
                        seen_lines+=" ${lineno} "
                        candidates+=("${lineno}"$'\t'"${pattern}"$'\t'"${line}")
                    done < <(_catalog_scan "$file" "$pattern")
                done

                violations=()
                exempted=()
                for entry in ${candidates[@]+"${candidates[@]}"}; do
                    IFS=$'\t' read -r lineno pattern line <<<"$entry"
                    literal="$(_catalog_match "$line" "$pattern")"
                    [[ -n "$literal" ]] || literal="$line"
                    if allowed_key="$(_catalog_allowed "$file" "$line")"; then
                        exempted+=("line ${lineno} (${allowed_key})")
                    else
                        violations+=("line ${lineno}: ${literal}")
                        report_fail "$file" "line ${lineno}: inline version literal ${literal} in '${line}'. It is not on the ${LOG_FILE} Requirement 9.8 allow-list, so declare the version in ${catalog} and reference it through a libs. accessor — or, if it genuinely cannot live in the catalog, record it in the log first"
                    fi
                done

                if (( ${#violations[@]} == 0 )); then
                    if (( ${#exempted[@]} == 0 )); then
                        report_pass "$file" "no inline version literal in a dependency coordinate; every external version comes from ${catalog}"
                    else
                        report_pass "$file" "no unmanaged version literal; the $(( ${#exempted[@]} )) literal(s) present are the ones ${LOG_FILE} documents — $(printf '%s, ' "${exempted[@]}" | sed -E 's/, $//')"
                    fi
                fi
            fi

            # 2b. The same invariant from the other side: every dependency declaration
            # names a libs. accessor (or project(...) for an internal module).
            bad_deps=()
            dep_count=0
            while IFS=$'\t' read -r lineno line; do
                [[ -n "$lineno" ]] || continue
                dep_count=$(( dep_count + 1 ))
                arg="${line#*(}"
                case "$arg" in
                    libs.*|platform\(libs.*|enforcedPlatform\(libs.*|project\(*|kotlin\(*) ;;
                    *)
                        bad_deps+=("$lineno")
                        report_fail "$file" "line ${lineno}: dependency declared as '${line}' rather than through a libs. accessor. Every external dependency resolves its version from ${catalog}; only project(…) may name a module directly"
                        ;;
                esac
            done < <(_catalog_scan "$file" "$catalog_dep_re")

            if (( dep_count == 0 )); then
                report_note "${file}: declares no dependencies, so only the literal and extra[…] invariants apply to it"
            elif (( ${#bad_deps[@]} == 0 )); then
                report_pass "$file" "all ${dep_count} dependency declarations go through a libs. accessor (or project(…) for an internal module)"
            fi
        done

        # --- Invariants 3 and 4, over the catalog --------------------------------------
        if [[ ! -f "$catalog" ]]; then
            report_fail "$catalog" "version catalog not found; the version.ref resolution and the two versionless entries cannot be checked, and no libs. accessor in the build files resolves without it"
            return 0
        fi

        local dump
        dump="$(_catalog_toml_dump "$catalog")"
        if [[ -z "$dump" ]]; then
            report_fail "$catalog" "catalog is empty or contains no declaration; every libs. accessor in the four build files resolves through it"
            return 0
        fi

        local -a parse_errors=() version_keys=() refs=() unresolved=()
        while IFS=$'\t' read -r kind lineno sect key value; do
            case "$kind" in
                BAD)
                    parse_errors+=("line ${lineno}: ${key}")
                    ;;
                ENTRY)
                    [[ "$sect" == "versions" ]] && version_keys+=("$key")
                    ;;
            esac
        done <<<"$dump"

        if (( ${#parse_errors[@]} == 0 )); then
            report_pass "$catalog" "parses: every non-comment line is a section header or a key = value declaration"
        else
            for entry in "${parse_errors[@]}"; do
                report_fail "$catalog" "does not parse — ${entry} is neither a section header nor a key = value declaration"
            done
        fi

        local missing_sections=""
        for sect in versions libraries plugins; do
            printf '%s\n' "$dump" | awk -F'\t' -v want="$sect" '$1 == "SECTION" && $3 == want { found = 1 } END { exit found ? 0 : 1 }' \
                || missing_sections+=" [${sect}]"
        done
        if [[ -n "$missing_sections" ]]; then
            report_fail "$catalog" "missing section(s):${missing_sections}; the catalog is the single source of truth for versions, libraries and plugins alike"
        fi

        if (( ${#version_keys[@]} == 0 )); then
            report_fail "$catalog" "[versions] declares no keys, so no version.ref in [libraries]/[plugins] can resolve"
            return 0
        fi

        local dupes
        dupes="$(printf '%s\n' "${version_keys[@]}" | sort | uniq -d | tr '\n' ' ' | sed -E 's/[[:space:]]+$//')"
        if [[ -n "$dupes" ]]; then
            report_fail "$catalog" "[versions] declares duplicate key(s): ${dupes}. The later declaration silently shadows the earlier one, so the value a libs. accessor resolves to is not the one a reader sees first"
        fi

        # version.ref resolution across [libraries] and [plugins].
        local ref
        while IFS=$'\t' read -r kind lineno sect key value; do
            [[ "$kind" == "ENTRY" ]] || continue
            case "$sect" in
                libraries|plugins|libraries.*|plugins.*) ;;
                *) continue ;;
            esac
            if [[ "$key" == "version.ref" ]]; then
                ref="${value//\"/}"
                ref="${ref// /}"
            else
                ref="$(printf '%s\n' "$value" | sed -nE 's/.*version[[:space:]]*\.[[:space:]]*ref[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p')"
            fi
            [[ -n "$ref" ]] || continue
            refs+=("$ref")
            if ! printf '%s\n' "${version_keys[@]}" | grep -qxF -- "$ref"; then
                unresolved+=("line ${lineno}: [${sect}] entry '${key}' references version.ref = \"${ref}\"")
            fi
        done <<<"$dump"

        if (( ${#refs[@]} == 0 )); then
            report_fail "$catalog" "no version.ref found in [libraries]/[plugins]; with ${#version_keys[@]} [versions] key(s) declared, nothing referencing them means the versions are not the ones in use"
        elif (( ${#unresolved[@]} == 0 )); then
            report_pass "$catalog" "all ${#refs[@]} version.ref declarations in [libraries]/[plugins] resolve to one of the ${#version_keys[@]} [versions] keys"
        else
            for entry in "${unresolved[@]}"; do
                report_fail "$catalog" "unresolvable version reference — ${entry}, which is not a key in [versions] (declared: $(printf '%s ' "${version_keys[@]}" | sed -E 's/[[:space:]]+$//'))"
            done
        fi

        # Context, not a result: a [versions] key nothing references is either dead or, like
        # flociImage, a value referenced from a build script rather than a library entry.
        local unreferenced=""
        for key in "${version_keys[@]}"; do
            printf '%s\n' ${refs[@]+"${refs[@]}"} | grep -qxF -- "$key" || unreferenced+="${key} "
        done
        [[ -z "$unreferenced" ]] || report_note "[versions] key(s) no [libraries]/[plugins] entry references: ${unreferenced% } — expected for a value a build script reads directly (the Floci Docker tag), suspect otherwise"

        # The two deliberately versionless entries.
        local want source_text found found_line found_value
        for want in aws-sdk-java-s3 junit-platform-launcher; do
            case "$want" in
                aws-sdk-java-s3) source_text="its version comes from platform(libs.aws.sdk.java.bom)" ;;
                *) source_text="its version is constrained by junit-jupiter" ;;
            esac

            found=""
            found_line=""
            found_value=""
            while IFS=$'\t' read -r kind lineno sect key value; do
                [[ "$kind" == "ENTRY" && "$sect" == "libraries" && "$key" == "$want" ]] || continue
                found="yes"
                found_line="$lineno"
                found_value="$value"
            done <<<"$dump"

            if [[ -z "$found" ]]; then
                report_fail "$catalog" "[libraries] has no '${want}' entry, yet ${LOG_FILE} records it as deliberately versionless (${source_text}); removing it moves the version somewhere the log does not describe"
            elif printf '%s\n' "$found_value" | grep -qE 'version[[:space:]]*([.][[:space:]]*ref)?[[:space:]]*='; then
                report_fail "$catalog" "line ${found_line}: '${want}' now declares its own version — '${found_value}'. It is one of the two entries that must stay versionless because ${source_text}; pinning it separately invites a split between the two"
            else
                report_pass "$catalog" "line ${found_line}: '${want}' is present and still versionless, as ${LOG_FILE} records — ${source_text}"
            fi
        done
    }

    _catalog_body
    unset -f _catalog_body _catalog_scan _catalog_match _catalog_line_matches _catalog_allowed _catalog_toml_dump
    return 0
}
