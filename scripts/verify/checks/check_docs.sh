# shellcheck shell=bash
#
# check_docs — the documentation enumerates exactly what the repository contains. Task 15.11.
#
# Three documents make claims about the repository that go stale silently:
#
#   README.md         the badge block, the module table, the pointers into the other two
#   SECURITY.md       one subsection per security/quality tool, each naming a checklist item
#   CONTRIBUTING.md   the workflow map, and the maintainer setup checklist
#
# A stale claim here is worse than no claim: a workflow that exists but is absent from the
# map is a workflow nobody reviews, and a checklist item that has drifted out of dependency
# order tells a maintainer to do something that cannot yet be done. Five assertions:
#
#   1. The CONTRIBUTING.md workflow map is bidirectional with `.github/workflows/`: every
#      file on disk has a row, and every filename the table names exists. Only the first
#      column counts as "named by the table" — the other columns mention workflows in prose.
#   2. Every relative markdown link in the three files resolves. An anchor link must also
#      find a heading in the target file whose GitHub slug equals the anchor, because a
#      link to `CONTRIBUTING.md#maintainer-setup-checklist` that lands at the top of the
#      file looks like it works.
#   3. Every numbered checklist item carries the four labelled sub-fields used in that file
#      and a status marker, and the numbering is gap-free from 1. The item count is read
#      from the file and cross-checked against the prose count in the section's intro, so
#      adding an item needs no edit here.
#   4. "Requires item N" always points backwards, so the checklist can be worked top to
#      bottom.
#   5. SECURITY.md has one subsection per tool, with the same labelled fields on each, says
#      `trufflehog-config.yml` is inert, links the checklist anchor, and cites no checklist
#      item number that does not exist.
#
# Everything is read with awk/sed rather than a markdown parser: these are the repository's
# own documents, and the shapes asserted above are the shapes they are written in.

check_docs() {
    # Local helpers live inside the function and are unset at the end: every file under
    # scripts/verify/checks/ is sourced into one shell, so a bare `_docs_*` at file scope
    # would leak into the other checks.

    # _docs_contains <needle> [item…] — set membership, exact match.
    _docs_contains() {
        local needle=$1
        shift
        local item
        for item in "$@"; do
            if [[ "$item" == "$needle" ]]; then
                return 0
            fi
        done
        return 1
    }

    # _docs_section <file> <heading regex> — the body under a `## ` heading, up to the next
    # `## ` heading.
    _docs_section() {
        awk -v pat="$2" '
            $0 ~ pat { inside = 1; next }
            inside && /^## / { exit }
            inside { print }
        ' "$1"
    }

    # _docs_subsection <section text> <### heading text> — the body of one `### ` subsection.
    _docs_subsection() {
        printf '%s\n' "$1" | awk -v want="### $2" '
            $0 == want { inside = 1; next }
            inside && /^### / { exit }
            inside { print }
        '
    }

    # _docs_headings <file> — heading text, fenced code blocks excluded so a `# comment`
    # inside a shell example is not mistaken for a heading.
    _docs_headings() {
        awk '
            /^[[:space:]]*```/ { fence = !fence; next }
            fence { next }
            /^#+[[:space:]]/ { sub(/^#+[[:space:]]+/, ""); print }
        ' "$1"
    }

    # _docs_slug <heading text> — GitHub's anchor slug: link syntax collapsed to its text,
    # lowercased, everything but letters, digits, underscore and hyphen dropped, spaces
    # turned into hyphens. Inline `code` and **bold** markers fall out of the character
    # filter, so they need no separate pass.
    _docs_slug() {
        printf '%s' "$1" \
            | sed -E 's/\[([^]]*)\]\([^)]*\)/\1/g' \
            | tr '[:upper:]' '[:lower:]' \
            | sed -E 's/[^a-z0-9 _-]+//g; s/^[[:space:]]+//; s/[[:space:]]+$//; s/[[:space:]]+/-/g'
    }

    # _docs_has_anchor <file> <anchor> — some heading in <file> slugs to <anchor>.
    _docs_has_anchor() {
        local target=$1 anchor=$2 heading
        while IFS= read -r heading; do
            if [[ "$(_docs_slug "$heading")" == "$anchor" ]]; then
                return 0
            fi
        done < <(_docs_headings "$target")
        return 1
    }

    # _docs_link_targets <file> — one link target per line, fenced code blocks excluded.
    # The pattern refuses brackets inside the link text, which is what makes the outer
    # `[![label](image)](target)` of a badge fall out: only the inner image URL matches,
    # and that is external, so it is skipped downstream.
    _docs_link_targets() {
        awk '
            /^[[:space:]]*```/ { fence = !fence; next }
            fence { next }
            {
                s = $0
                while (match(s, /\[[^][]*\]\([^()]*\)/)) {
                    m = substr(s, RSTART, RLENGTH)
                    s = substr(s, RSTART + RLENGTH)
                    sub(/^\[[^][]*\]\(/, "", m)
                    sub(/\)$/, "", m)
                    print m
                }
            }
        ' "$1"
    }

    # _docs_word_to_number <word> — the number words the documents use for their own counts.
    # Prints nothing for anything else, which the caller reports as a named failure rather
    # than passing silently.
    _docs_word_to_number() {
        local word
        word="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
        case "$word" in
            one) printf '1\n' ;;
            two) printf '2\n' ;;
            three) printf '3\n' ;;
            four) printf '4\n' ;;
            five) printf '5\n' ;;
            six) printf '6\n' ;;
            seven) printf '7\n' ;;
            eight) printf '8\n' ;;
            nine) printf '9\n' ;;
            ten) printf '10\n' ;;
            eleven) printf '11\n' ;;
            twelve) printf '12\n' ;;
            thirteen) printf '13\n' ;;
            fourteen) printf '14\n' ;;
            fifteen) printf '15\n' ;;
            sixteen) printf '16\n' ;;
            seventeen) printf '17\n' ;;
            eighteen) printf '18\n' ;;
            nineteen) printf '19\n' ;;
            twenty) printf '20\n' ;;
            *[!0-9]*) ;;
            '') ;;
            *) printf '%s\n' "$word" ;;
        esac
    }

    # _docs_checklist_records <file> — one tab-separated record per numbered checklist item:
    #
    #   num  marker  owner  unblocks  ifskipped  confirmby  requires(comma|-)  title
    #
    # The "If skipped" label is matched by prefix: one item qualifies it
    # ("**If skipped, or if default setup is enabled instead:**"), and that is still the
    # same field. Non-numbered `### ` headings inside the section (the closing
    # "not open items of this one" note) end the current item and start no new one.
    _docs_checklist_records() {
        awk '
            function flush() {
                if (num != "") {
                    printf "%s\t%s\t%d\t%d\t%d\t%d\t%s\t%s\n", \
                        num, marker, owner, unblocks, skipped, confirm, (req == "" ? "-" : req), title
                }
                num = ""; marker = "-"; owner = 0; unblocks = 0; skipped = 0; confirm = 0
                req = ""; title = ""
            }
            BEGIN { inside = 0; num = ""; marker = "-" }
            /^## / {
                if ($0 ~ /^## Maintainer setup checklist[[:space:]]*$/) { inside = 1; next }
                if (inside) { flush(); inside = 0 }
                next
            }
            inside == 0 { next }
            /^### / {
                flush()
                if (match($0, /^### [0-9]+\./)) {
                    line = $0
                    sub(/^### /, "", line)
                    num = line; sub(/\..*$/, "", num)
                    title = line; sub(/^[0-9]+\.[[:space:]]*/, "", title)
                    if (title ~ /`\[ \] open`/)       marker = "[ ] open"
                    else if (title ~ /`\[x\] done`/)  marker = "[x] done"
                    else                              marker = "-"
                    sub(/[[:space:]]*`\[[^]]*\][^`]*`[[:space:]]*$/, "", title)
                }
                next
            }
            num == "" { next }
            /^[[:space:]]*-[[:space:]]+\*\*Owner:\*\*/       { owner = 1 }
            /^[[:space:]]*-[[:space:]]+\*\*Unblocks:\*\*/     { unblocks = 1 }
            /^[[:space:]]*-[[:space:]]+\*\*If skipped[^*]*:\*\*/ { skipped = 1 }
            /^[[:space:]]*-[[:space:]]+\*\*Confirm by:\*\*/   { confirm = 1 }
            {
                s = $0
                while (match(s, /[Rr]equires item [0-9]+/)) {
                    m = substr(s, RSTART, RLENGTH)
                    s = substr(s, RSTART + RLENGTH)
                    gsub(/[^0-9]/, "", m)
                    req = req (req == "" ? "" : ",") m
                }
            }
            END { flush() }
        ' "$1"
    }

    # --- 1. the workflow map is bidirectional with .github/workflows/ ---------------------
    _docs_check_workflow_map() {
        local file=$1 section
        section="$(_docs_section "$file" '^## Workflow map')"
        if [[ -z "$section" ]]; then
            report_fail "$file" "no '## Workflow map' section, so the workflows under .github/workflows/ are documented nowhere"
            return 0
        fi

        local -a documented=() disk=()
        local name
        while IFS= read -r name; do
            [[ -n "$name" ]] && documented+=("$name")
        done < <(printf '%s\n' "$section" \
            | grep -E '^\|' \
            | sed -E 's/^\|//; s/\|.*$//; s/[^A-Za-z0-9._-]//g' \
            | grep -E '\.ya?ml$' || true)

        local f
        for f in .github/workflows/*.yml .github/workflows/*.yaml; do
            [[ -f "$f" ]] || continue
            disk+=("${f##*/}")
        done

        if (( ${#disk[@]} == 0 )); then
            report_fail ".github/workflows" "no workflow files found, so the workflow map in ${file} cannot be checked against them"
            return 0
        fi

        local failures=0 item
        for item in "${disk[@]}"; do
            if ! _docs_contains "$item" "${documented[@]:-}"; then
                report_fail "$file" "the workflow map has no row for .github/workflows/${item}; every file under .github/workflows/ needs one, or the workflow is one nobody reviews"
                failures=$((failures + 1))
            fi
        done
        for item in "${documented[@]:-}"; do
            [[ -n "$item" ]] || continue
            if ! _docs_contains "$item" "${disk[@]}"; then
                report_fail "$file" "the workflow map names '${item}' in its first column, but .github/workflows/${item} does not exist"
                failures=$((failures + 1))
            fi
        done

        local dupes
        dupes="$(printf '%s\n' "${documented[@]:-}" | sort | uniq -d | tr '\n' ' ' | sed -E 's/[[:space:]]+$//' || true)"
        if [[ -n "$dupes" ]]; then
            report_fail "$file" "the workflow map lists the same workflow more than once: ${dupes}"
            failures=$((failures + 1))
        fi

        if (( failures == 0 )); then
            report_pass "$file" "the workflow map has exactly one row per file under .github/workflows/ and names no missing file (${#disk[@]} workflows)"
        fi
    }

    # --- 2. every relative link resolves, anchors included -------------------------------
    _docs_check_links() {
        local file target path anchor resolved dir checked unresolved
        for file in "$@"; do
            dir="$(dirname "$file")"
            checked=0
            unresolved=0
            while IFS= read -r target; do
                [[ -n "$target" ]] || continue
                target="${target%% *}"   # drop an optional link title
                [[ -n "$target" ]] || continue
                case "$target" in
                    http://*|https://*|mailto:*) continue ;;
                esac

                anchor=""
                path="$target"
                if [[ "$target" == *"#"* ]]; then
                    anchor="${target#*#}"
                    path="${target%%#*}"
                fi
                [[ -n "$path" ]] || path="${file##*/}"

                resolved="${dir%/}/${path}"
                checked=$((checked + 1))

                if [[ ! -e "$resolved" ]]; then
                    report_fail "$file" "link target '${target}' does not resolve: ${resolved} does not exist"
                    unresolved=$((unresolved + 1))
                    continue
                fi
                [[ -n "$anchor" ]] || continue

                if [[ "$resolved" != *.md ]]; then
                    report_fail "$file" "link '${target}' carries the anchor '#${anchor}', but ${resolved} is not a markdown file, so no heading can match it"
                    unresolved=$((unresolved + 1))
                elif ! _docs_has_anchor "$resolved" "$anchor"; then
                    report_fail "$file" "link '${target}' resolves to ${resolved} but no heading there has the GitHub slug '${anchor}', so the link lands at the top of the file instead of the section it names"
                    unresolved=$((unresolved + 1))
                fi
            done < <(_docs_link_targets "$file")

            if (( unresolved == 0 )); then
                report_pass "$file" "every relative link resolves, anchors included (${checked} checked; http(s) and mailto links are not this script's business)"
            fi
        done
    }

    # --- 3 and 4. checklist shape, numbering and dependency order ------------------------
    _docs_check_checklist() {
        local file=$1
        local headings
        headings="$(grep -c '^## Maintainer setup checklist' "$file" || true)"
        headings="${headings//[^0-9]/}"
        if [[ "${headings:-0}" != "1" ]]; then
            report_fail "$file" "expected exactly one '## Maintainer setup checklist' heading, found ${headings:-0}; SECURITY.md and README.md both link that anchor"
            return 0
        fi

        local records
        records="$(_docs_checklist_records "$file")"
        if [[ -z "$records" ]]; then
            report_fail "$file" "the '## Maintainer setup checklist' section contains no '### <n>. <title>' item"
            return 0
        fi

        local num marker owner unblocks skipped confirm requires title
        local -a nums=()
        local total=0 shape_failures=0 marker_failures=0 dep_failures=0 dep_refs=0
        local -a lacks=()
        local dep

        while IFS=$'\t' read -r num marker owner unblocks skipped confirm requires title; do
            [[ -n "$num" ]] || continue
            total=$((total + 1))
            nums+=("$num")

            lacks=()
            (( owner == 1 ))    || lacks+=("**Owner:**")
            (( unblocks == 1 )) || lacks+=("**Unblocks:**")
            (( skipped == 1 ))  || lacks+=("**If skipped:**")
            (( confirm == 1 ))  || lacks+=("**Confirm by:**")
            if (( ${#lacks[@]} > 0 )); then
                report_fail "$file" "checklist item ${num} ('${title}') is missing $(printf '%s ' "${lacks[@]}" | sed -E 's/[[:space:]]+$//'); every item states who owns it, what it unblocks, the symptom if it is skipped, and how to confirm it"
                shape_failures=$((shape_failures + 1))
            fi

            if [[ "$marker" == "-" ]]; then
                report_fail "$file" "checklist item ${num} ('${title}') carries no status marker; its heading must end in \`[ ] open\` or \`[x] done\`"
                marker_failures=$((marker_failures + 1))
            fi

            if [[ "$requires" != "-" ]]; then
                for dep in ${requires//,/ }; do
                    dep_refs=$((dep_refs + 1))
                    if (( dep >= num )); then
                        report_fail "$file" "checklist item ${num} says 'Requires item ${dep}', which is not earlier on the list; a dependency must come before the item that needs it so the checklist can be worked top to bottom"
                        dep_failures=$((dep_failures + 1))
                    fi
                done
            fi
        done <<< "$records"

        if (( shape_failures == 0 )); then
            report_pass "$file" "all ${total} checklist items carry **Owner:**, **Unblocks:**, **If skipped…:** and **Confirm by:**"
        fi
        if (( marker_failures == 0 )); then
            report_pass "$file" "all ${total} checklist items carry a status marker (\`[ ] open\` or \`[x] done\`)"
        fi
        if (( dep_failures == 0 )); then
            report_pass "$file" "all ${dep_refs} 'Requires item N' reference(s) point at a lower-numbered item"
        fi

        local expected=1 gap=0 sorted
        sorted="$(printf '%s\n' "${nums[@]}" | sort -n)"
        while IFS= read -r num; do
            [[ -n "$num" ]] || continue
            if [[ "$num" != "$expected" ]]; then
                report_fail "$file" "checklist numbering is not gap-free: expected item ${expected}, found ${num} (items on the list: $(printf '%s\n' "${nums[@]}" | sort -n | tr '\n' ' ' | sed -E 's/[[:space:]]+$//'))"
                gap=1
                break
            fi
            expected=$((expected + 1))
        done <<< "$sorted"
        if (( gap == 0 )); then
            report_pass "$file" "checklist items are numbered 1..${total} with no gap and no duplicate"
        fi

        # The prose count in the section intro against the items actually present, so the
        # two cannot drift when an item is added.
        local count_word claimed
        count_word="$(grep -Eo '^These [A-Za-z0-9]+ actions' "$file" | head -n 1 | awk '{print $2}' || true)"
        if [[ -z "$count_word" ]]; then
            report_fail "$file" "the checklist section has no 'These <count> actions …' intro sentence, so the prose count cannot be held to the ${total} items on the list"
        else
            claimed="$(_docs_word_to_number "$count_word")"
            if [[ -z "$claimed" ]]; then
                report_fail "$file" "the checklist intro says 'These ${count_word} actions …' and '${count_word}' is not a count this check can read; write it as a number word (one … twenty) or a numeral"
            elif [[ "$claimed" != "$total" ]]; then
                report_fail "$file" "the checklist intro says 'These ${count_word} actions …' (${claimed}) but the section lists ${total} items"
            else
                report_pass "$file" "the checklist intro's count ('${count_word}') matches the ${total} items on the list"
            fi
        fi
    }

    # --- 5. SECURITY.md: one subsection per tool, inert config named, anchor linked -------
    _docs_check_security() {
        local file=$1 contributing=$2 section
        section="$(_docs_section "$file" '^## Security and quality tooling')"
        if [[ -z "$section" ]]; then
            report_fail "$file" "no '## Security and quality tooling' section, so no tool in this repository is documented there"
            return 0
        fi

        local -a tools=()
        local tool
        while IFS= read -r tool; do
            [[ -n "$tool" ]] && tools+=("$tool")
        done < <(printf '%s\n' "$section" | sed -nE 's/^### (.+)$/\1/p' || true)

        if (( ${#tools[@]} == 0 )); then
            report_fail "$file" "the '## Security and quality tooling' section has no '### <tool>' subsection"
            return 0
        fi

        # The section's own count sentence against the subsections present.
        local count_word claimed
        count_word="$(printf '%s\n' "$section" | grep -Eo '[A-Za-z0-9]+ tools guard' | head -n 1 | awk '{print $1}' || true)"
        if [[ -z "$count_word" ]]; then
            report_fail "$file" "the tooling section has no '<count> tools guard …' sentence, so the prose count cannot be held to the ${#tools[@]} subsections present"
        else
            claimed="$(_docs_word_to_number "$count_word")"
            if [[ -z "$claimed" ]]; then
                report_fail "$file" "the tooling section says '${count_word} tools guard …' and '${count_word}' is not a count this check can read; write it as a number word (one … twenty) or a numeral"
            elif [[ "$claimed" != "${#tools[@]}" ]]; then
                report_fail "$file" "the tooling section says '${count_word} tools guard …' (${claimed}) but it has ${#tools[@]} subsections: $(printf '%s, ' "${tools[@]}" | sed -E 's/, $//')"
            else
                report_pass "$file" "one subsection per tool: the section's count ('${count_word}') matches its ${#tools[@]} subsections"
            fi
        fi

        # The labelled fields every tool entry is written with, taken from the first entry
        # rather than hardcoded, so adding a field to the pattern needs no edit here.
        local -a labels=()
        local label
        while IFS= read -r label; do
            [[ -n "$label" ]] && labels+=("$label")
        done < <(_docs_subsection "$section" "${tools[0]}" \
            | sed -nE 's/^-[[:space:]]+\*\*([^*:]+):\*\*.*/\1/p' || true)

        if (( ${#labels[@]} == 0 )); then
            report_fail "$file" "the '${tools[0]}' subsection carries no '- **Label:**' fields, so there is no entry shape to hold the other $(( ${#tools[@]} - 1 )) subsections to"
        else
            local body field_failures=0
            local -a lacks=()
            for tool in "${tools[@]}"; do
                body="$(_docs_subsection "$section" "$tool")"
                if [[ -z "$body" ]]; then
                    report_fail "$file" "the '${tool}' subsection has no body"
                    field_failures=$((field_failures + 1))
                    continue
                fi
                lacks=()
                for label in "${labels[@]}"; do
                    printf '%s\n' "$body" | grep -qE "^-[[:space:]]+\*\*${label}:\*\*" \
                        || lacks+=("**${label}:**")
                done
                if (( ${#lacks[@]} > 0 )); then
                    report_fail "$file" "the '${tool}' subsection is missing $(printf '%s ' "${lacks[@]}" | sed -E 's/[[:space:]]+$//'); every tool entry states the same fields, or a reader cannot compare two tools"
                    field_failures=$((field_failures + 1))
                fi
            done
            if (( field_failures == 0 )); then
                report_pass "$file" "all ${#tools[@]} tool subsections carry the same $(printf '%s ' "${labels[@]}" | sed -E 's/[[:space:]]+$//') fields"
            fi
        fi

        # The one committed config nothing reads must say so, or a reader assumes it works.
        local inert_config="trufflehog-config.yml"
        if printf '%s\n' "$section" | grep -qiE "${inert_config//./\\.}[^.]*inert|inert[^.]*${inert_config//./\\.}"; then
            report_pass "$file" "states that ${inert_config} is inert, so a reader does not assume the committed config is being consumed"
        else
            report_fail "$file" "does not state that ${inert_config} is inert; it is committed but nothing in this repository reads it, and the tooling section is where that is recorded"
        fi

        # The checklist anchor, and every checklist item number the file cites.
        local anchor_link="${contributing}#maintainer-setup-checklist"
        if grep -qF -- "](${anchor_link})" "$file"; then
            report_pass "$file" "links the maintainer setup checklist as ${anchor_link}, so every maintainer action has one home"
        else
            report_fail "$file" "links no '${anchor_link}' anchor; the maintainer actions it names are listed once, in ${contributing}, and that is the link to them"
        fi

        local checklist_total
        checklist_total="$(_docs_checklist_records "$contributing" | grep -c '' || true)"
        checklist_total="${checklist_total//[^0-9]/}"
        checklist_total="${checklist_total:-0}"

        local -a cited=()
        local cite
        while IFS= read -r cite; do
            [[ -n "$cite" ]] && cited+=("$cite")
        done < <(printf '%s\n' "$section" \
            | grep -Eo 'items?[[:space:]]+\*\*[0-9]+\*\*' \
            | grep -Eo '[0-9]+' | sort -n -u || true)

        if (( ${#cited[@]} == 0 )); then
            report_fail "$file" "cites no checklist item number (expected 'checklist item **N**'), so no tool entry points at the action that activates it"
        else
            local bad=0
            for cite in "${cited[@]}"; do
                if (( cite < 1 || cite > checklist_total )); then
                    report_fail "$file" "cites checklist item ${cite}, but ${contributing} lists items 1..${checklist_total}"
                    bad=$((bad + 1))
                fi
            done
            if (( bad == 0 )); then
                report_pass "$file" "every checklist item number it cites ($(printf '%s ' "${cited[@]}" | sed -E 's/[[:space:]]+$//')) exists on the ${checklist_total}-item checklist in ${contributing}"
            fi
        fi
    }

    _docs_body() {
        local readme="README.md" security="SECURITY.md" contributing="CONTRIBUTING.md"
        local missing=0 file
        for file in "$readme" "$security" "$contributing"; do
            if [[ ! -f "$file" ]]; then
                report_fail "$file" "file not found; the documentation coverage checks cannot run"
                missing=1
            fi
        done
        (( missing == 0 )) || return 0

        _docs_check_workflow_map "$contributing"
        _docs_check_links "$readme" "$security" "$contributing"
        _docs_check_checklist "$contributing"
        _docs_check_security "$security" "$contributing"

        report_note "counts are read from the documents (checklist items, tool subsections, workflow files on disk), so adding an item or a workflow needs no edit to this check — only the documents it holds to each other."
    }

    _docs_body
    unset -f _docs_body _docs_check_workflow_map _docs_check_links _docs_check_checklist \
        _docs_check_security _docs_checklist_records _docs_word_to_number \
        _docs_link_targets _docs_has_anchor _docs_slug _docs_headings _docs_subsection \
        _docs_section _docs_contains
    return 0
}
