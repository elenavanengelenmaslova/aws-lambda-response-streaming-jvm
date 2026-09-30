# shellcheck shell=bash
#
# check_badge_sources — every README badge agrees with the file that decides its value.
# Task 15.3; Requirements 12.1, 12.2, 12.5, 12.6, 12.7.
#
# check_badge_block (task 15.2) asserts the badge block is the expected literal: the right
# badges, in the right order. This check asserts the other half — that the literal is still
# *true*. A badge is a claim about something else in the repository, and the claim rots
# silently: bumping `kotlin` in the version catalog, raising the library toolchain, or
# renaming a workflow file leaves the badge rendering a stale value that nobody reads twice.
#
# So every source value here is read at runtime. Nothing hardcodes 2.3.0, 21, or the
# coordinates; a drift between README and source is the failure, not a mismatch against a
# constant baked into this file that would itself need updating.
#
# Six pairings:
#
#   1. Kotlin badge         ← `kotlin = "…"` in gradle/libs.versions.toml
#   2. JVM badge            ← :streaming-core's toolchain in streaming-core/build.gradle.kts
#   3. Maven Central badge  ← coordinates(…) in streaming-core/build.gradle.kts
#   4. Build Status badge   ← .github/workflows/ci-main-build.yml (named, and present)
#   5. CodeQL badge         ← .github/workflows/codeql.yml (named, and present)
#   6. repo slug, all URLs  ← the git remote
#
# Not asserted here: the three-way licence equality (LICENSE ↔ the streaming-core POM ↔ the
# licence badge label). check_licence.sh in this directory owns it.
#
# On the JVM badge specifically: the source of truth is :streaming-core's Java 21, the
# *library's* toolchain — deliberately not the example modules' Java 25. A library's
# bytecode target is a floor for every consumer, so the badge advertises the floor. A reader
# who "fixes" the badge to 25 because the examples say 25 is the exact mistake this pairing
# is here to catch.

check_badge_sources() {
    local readme="README.md"
    local catalog="gradle/libs.versions.toml"
    local core_build="streaming-core/build.gradle.kts"
    local workflows_dir=".github/workflows"

    local missing=0 file
    for file in "$readme" "$catalog" "$core_build"; do
        if [[ ! -f "$file" ]]; then
            report_fail "$file" "file not found; the README badges cannot be compared against their sources of truth"
            missing=1
        fi
    done
    (( missing == 0 )) || return 0

    # --- Badge-line accessors -------------------------------------------------------------
    # The badge block writes one badge per line as [![label](image)](target). Names are
    # prefixed because every scripts/verify/checks/*.sh is sourced into one shell.

    # _badge_sources_line <label> — the single badge line with that exact label, or nothing.
    _badge_sources_line() {
        grep -m1 -F -- "[![${1}](" "$readme" || true
    }
    # _badge_sources_image <line> — the first (…) group: the image URL.
    _badge_sources_image() {
        printf '%s\n' "$1" | sed -E 's/^[^]]*\]\(([^)]*)\).*/\1/'
    }
    # _badge_sources_target <line> — the last (…) group: the click-through URL.
    _badge_sources_target() {
        printf '%s\n' "$1" | sed -E 's/.*\]\(([^)]*)\)[[:space:]]*$/\1/'
    }
    # _badge_sources_shields_message <image url> — the message of a shields static badge,
    # /badge/<label>-<message>-<colour>.svg. Prints nothing when the URL is not that shape.
    _badge_sources_shields_message() {
        printf '%s\n' "$1" \
            | sed -nE 's|^https://img\.shields\.io/badge/[^/-]+-([^-]+)-[^-]+\.svg.*$|\1|p'
    }

    # --- 1. Kotlin badge vs the version catalog -------------------------------------------
    local kotlin_line kotlin_badge kotlin_catalog
    kotlin_line="$(_badge_sources_line "Kotlin")"
    kotlin_catalog="$(grep -m1 -E '^[[:space:]]*kotlin[[:space:]]*=' "$catalog" \
        | sed -nE 's/.*"([^"]+)".*/\1/p')"

    if [[ -z "$kotlin_catalog" ]]; then
        report_fail "$catalog" "no [versions] entry 'kotlin = \"…\"' found, so the Kotlin badge has no source of truth to agree with"
    elif [[ -z "$kotlin_line" ]]; then
        report_fail "$readme" "no [![Kotlin](…)] badge found; ${catalog} declares kotlin = '${kotlin_catalog}' and nothing advertises it"
    else
        kotlin_badge="$(_badge_sources_shields_message "$(_badge_sources_image "$kotlin_line")")"
        if [[ -z "$kotlin_badge" ]]; then
            report_fail "$readme" "the Kotlin badge image is not a shields static badge (/badge/<label>-<message>-<colour>.svg), so its version cannot be read and compared against ${catalog}'s kotlin = '${kotlin_catalog}'"
        elif [[ "$kotlin_badge" == "$kotlin_catalog" ]]; then
            report_pass "the Kotlin badge" "reads '${kotlin_badge}', matching kotlin = '${kotlin_catalog}' in ${catalog}"
        else
            report_fail "the Kotlin badge" "reads '${kotlin_badge}' but ${catalog} declares kotlin = '${kotlin_catalog}'; the catalog is the single source of truth, so update the badge"
        fi
    fi

    # --- 2. JVM badge vs :streaming-core's toolchain --------------------------------------
    # The library's Java 21, not the examples' Java 25 — see the header comment.
    local jvm_line jvm_badge toolchain_version jvm_target
    toolchain_version="$(grep -m1 -Eo 'JavaLanguageVersion\.of\([[:space:]]*[0-9]+[[:space:]]*\)' "$core_build" \
        | grep -Eo '[0-9]+' || true)"
    jvm_target="$(grep -m1 -Eo 'JvmTarget\.JVM_[0-9]+' "$core_build" \
        | sed -E 's/.*JVM_//' || true)"
    jvm_line="$(_badge_sources_line "JVM")"

    if [[ -z "$toolchain_version" ]]; then
        report_fail "$core_build" "no JavaLanguageVersion.of(N) toolchain declaration found, so the JVM badge has no source of truth to agree with"
    elif [[ -z "$jvm_target" ]]; then
        report_fail "$core_build" "no JvmTarget.JVM_N compiler option found, so the Kotlin bytecode target cannot be confirmed against the toolchain's '${toolchain_version}'"
    elif [[ "$toolchain_version" != "$jvm_target" ]]; then
        report_fail "$core_build" "the toolchain says Java '${toolchain_version}' but jvmTarget says JVM_${jvm_target}; :streaming-core has to agree with itself before the JVM badge can agree with it"
    elif [[ -z "$jvm_line" ]]; then
        report_fail "$readme" "no [![JVM](…)] badge found; :streaming-core targets Java '${toolchain_version}' and nothing advertises it"
    else
        jvm_badge="$(_badge_sources_shields_message "$(_badge_sources_image "$jvm_line")")"
        if [[ -z "$jvm_badge" ]]; then
            report_fail "$readme" "the JVM badge image is not a shields static badge (/badge/<label>-<message>-<colour>.svg), so its version cannot be read and compared against :streaming-core's Java '${toolchain_version}'"
        elif [[ "$jvm_badge" == "$toolchain_version" ]]; then
            report_pass "the JVM badge" "reads '${jvm_badge}', matching :streaming-core's toolchain and jvmTarget (JavaLanguageVersion.of(${toolchain_version}) / JvmTarget.JVM_${jvm_target}) in ${core_build}"
        else
            report_fail "the JVM badge" "reads '${jvm_badge}' but :streaming-core targets Java '${toolchain_version}' (JavaLanguageVersion.of(${toolchain_version}) / JvmTarget.JVM_${jvm_target} in ${core_build}). The badge advertises the library's floor, not the example modules' Java version"
        fi
    fi

    # --- 3. Maven Central badge vs the published coordinates ------------------------------
    # coordinates("<group>", "<artifact>", version) in the mavenPublishing block. Both the
    # shields image (/maven-central/v/<group>/<artifact>) and the click-through
    # (central.sonatype.com/artifact/<group>/<artifact>) name the artifact, so both are
    # compared: a half-renamed artifact renders a version for one coordinate and links to
    # another.
    local coords_raw coords_group coords_artifact coords mc_line mc_image mc_target mc_image_coords mc_target_coords
    coords_raw="$(grep -m1 -Eo 'coordinates\([[:space:]]*"[^"]+"[[:space:]]*,[[:space:]]*"[^"]+"' "$core_build" || true)"
    if [[ -z "$coords_raw" ]]; then
        report_fail "$core_build" "no coordinates(\"<group>\", \"<artifact>\", …) call found in the mavenPublishing block, so the Maven Central badge has no source of truth to agree with"
    else
        coords_group="$(printf '%s\n' "$coords_raw" | sed -E 's/[^"]*"([^"]+)".*/\1/')"
        coords_artifact="$(printf '%s\n' "$coords_raw" | sed -E 's/[^"]*"[^"]+"[^"]*"([^"]+)".*/\1/')"
        coords="${coords_group}/${coords_artifact}"

        mc_line="$(_badge_sources_line "Maven Central")"
        if [[ -z "$mc_line" ]]; then
            report_fail "$readme" "no [![Maven Central](…)] badge found; ${core_build} publishes '${coords_group}:${coords_artifact}' and nothing advertises it"
        else
            mc_image="$(_badge_sources_image "$mc_line")"
            mc_target="$(_badge_sources_target "$mc_line")"
            mc_image_coords="$(printf '%s\n' "$mc_image" \
                | sed -nE 's|^https://img\.shields\.io/maven-central/v/([^/?]+)/([^/?]+).*$|\1/\2|p')"
            mc_target_coords="$(printf '%s\n' "$mc_target" \
                | sed -nE 's|^https://central\.sonatype\.com/artifact/([^/?]+)/([^/?]+).*$|\1/\2|p')"

            if [[ -z "$mc_image_coords" ]]; then
                report_fail "the Maven Central badge" "the image URL '${mc_image}' is not of the form https://img.shields.io/maven-central/v/<group>/<artifact>, so its coordinates cannot be compared against '${coords}' from ${core_build}"
            elif [[ "$mc_image_coords" == "$coords" ]]; then
                report_pass "the Maven Central badge image" "names '${mc_image_coords}', matching coordinates(\"${coords_group}\", \"${coords_artifact}\", …) in ${core_build}"
            else
                report_fail "the Maven Central badge image" "names '${mc_image_coords}' but ${core_build} publishes coordinates(\"${coords_group}\", \"${coords_artifact}\", …), i.e. '${coords}'"
            fi

            if [[ -z "$mc_target_coords" ]]; then
                report_fail "the Maven Central badge target" "the link '${mc_target}' is not of the form https://central.sonatype.com/artifact/<group>/<artifact>, so its coordinates cannot be compared against '${coords}' from ${core_build}"
            elif [[ "$mc_target_coords" == "$coords" ]]; then
                report_pass "the Maven Central badge target" "links to '${mc_target_coords}', matching the published coordinates in ${core_build}"
            else
                report_fail "the Maven Central badge target" "links to '${mc_target_coords}' but ${core_build} publishes '${coords}'"
            fi
        fi
    fi

    # --- 4 and 5. Workflow badges vs the workflow files -----------------------------------
    # A GitHub workflow badge embeds the workflow's *filename*:
    #   https://github.com/<slug>/actions/workflows/<file>/badge.svg
    # Rename the file and the badge renders "no status" rather than failing anything, so the
    # filename is compared against the expected workflow and the file's existence asserted.
    # The Build Status badge also clicks through to the same workflow; the CodeQL badge
    # clicks through to the security tab instead, so only its image is examined.
    local spec badge_label expected_workflow check_target line image named
    for spec in "Build Status|ci-main-build.yml|target" "CodeQL|codeql.yml|image-only"; do
        badge_label="${spec%%|*}"
        expected_workflow="$(printf '%s\n' "$spec" | cut -d'|' -f2)"
        check_target="$(printf '%s\n' "$spec" | cut -d'|' -f3)"

        line="$(_badge_sources_line "$badge_label")"
        if [[ -z "$line" ]]; then
            report_fail "$readme" "no [![${badge_label}](…)] badge found; it is the badge that has to name ${workflows_dir}/${expected_workflow}"
            continue
        fi

        image="$(_badge_sources_image "$line")"
        named="$(printf '%s\n' "$image" | sed -nE 's|^.*/actions/workflows/([^/]+)/badge\.svg.*$|\1|p')"
        if [[ -z "$named" ]]; then
            report_fail "the ${badge_label} badge" "the image URL '${image}' does not name a workflow (expected …/actions/workflows/${expected_workflow}/badge.svg)"
        elif [[ "$named" != "$expected_workflow" ]]; then
            report_fail "the ${badge_label} badge" "names workflow '${named}' but should report on '${expected_workflow}'"
        elif [[ ! -f "${workflows_dir}/${named}" ]]; then
            report_fail "the ${badge_label} badge" "names workflow '${named}' but ${workflows_dir}/${named} does not exist, so the badge renders no status"
        else
            report_pass "the ${badge_label} badge" "names '${named}', and ${workflows_dir}/${named} exists"
        fi

        if [[ "$check_target" == "target" ]]; then
            local target target_named
            target="$(_badge_sources_target "$line")"
            target_named="$(printf '%s\n' "$target" | sed -nE 's|^.*/actions/workflows/([^/?]+).*$|\1|p')"
            if [[ "$target_named" == "$expected_workflow" ]]; then
                report_pass "the ${badge_label} badge target" "links to the same workflow it reports on ('${expected_workflow}')"
            else
                report_fail "the ${badge_label} badge target" "links to '${target_named:-$target}' but the badge reports on '${expected_workflow}'; image and link must name one workflow"
            fi
        fi
    done

    # --- 6. The repo slug in every badge URL vs the git remote ---------------------------
    # One wrong slug points a badge at somebody else's repository, which still renders and
    # therefore still looks fine. Three URL shapes carry the slug:
    #   github.com/<owner>/<repo>…                          (release, workflow, security)
    #   codecov.io/gh/<owner>/<repo>…                       (coverage)
    #   img.shields.io/github/…/<owner>/<repo>              (release image)
    local remote remote_slug
    remote="$(git config --get remote.origin.url 2>/dev/null || true)"
    if [[ -z "$remote" ]]; then
        report_skip "the badge repo slugs" "no 'remote.origin.url' configured, so there is no remote slug to compare the badge URLs against"
    else
        # git@github.com:owner/repo.git and https://github.com/owner/repo.git both reduce to
        # the trailing two path segments with any .git suffix dropped.
        remote_slug="$(printf '%s\n' "$remote" \
            | sed -E 's#\.git$##; s#^[^:]+://[^/]+/##; s#^[^@]+@[^:]+:##')"
        if [[ ! "$remote_slug" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
            report_fail "the badge repo slugs" "could not read an <owner>/<repo> slug from remote.origin.url '${remote}' (got '${remote_slug}')"
        else
            local badge_lines slugs bad_slugs=() slug
            badge_lines="$(grep -E '^[[:space:]]*\[!\[' "$readme" || true)"
            slugs="$(printf '%s\n' "$badge_lines" | grep -Eo \
                -e 'github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+' \
                -e 'codecov\.io/gh/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+' \
                -e 'img\.shields\.io/github/[A-Za-z0-9_./-]*/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+' \
                | sed -E 's#^github\.com/##; s#^codecov\.io/gh/##; s#^img\.shields\.io/github/.*/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)$#\1#' \
                | sort -u || true)"

            if [[ -z "$slugs" ]]; then
                report_fail "$readme" "no badge URL carries an <owner>/<repo> slug, so none of them can be pointing at '${remote_slug}'"
            else
                while IFS= read -r slug; do
                    [[ -n "$slug" ]] || continue
                    [[ "$slug" == "$remote_slug" ]] || bad_slugs+=("$slug")
                done <<<"$slugs"

                if (( ${#bad_slugs[@]} == 0 )); then
                    report_pass "the badge repo slugs" "every badge URL names '${remote_slug}', matching remote.origin.url"
                else
                    report_fail "the badge repo slugs" "badge URLs name $(printf '%s ' "${bad_slugs[@]}" | sed -E 's/ $//' | tr ' ' ',' | sed -E "s/,/', '/g; s/^/'/; s/$/'/") but remote.origin.url is '${remote_slug}'"
                fi
            fi
        fi
    fi

    unset -f _badge_sources_line _badge_sources_image _badge_sources_target \
        _badge_sources_shields_message
}
