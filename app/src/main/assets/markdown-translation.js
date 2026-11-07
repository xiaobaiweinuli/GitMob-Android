/* App-owned DOM updates only. Translation requests and credentials stay in Kotlin. */
(() => {
    "use strict";
    const root = document.getElementById("gm-root");
    if (!root) return;
    const documentId = root.getAttribute("data-gm-document");
    const units = new Map();
    root.querySelectorAll("[data-gm-unit]").forEach(node => {
        units.set(Number(node.getAttribute("data-gm-unit")), { node, original: node.textContent });
    });
    const outputs = [];
    root.querySelectorAll("[data-gm-output]").forEach(node => {
        const output = { node, completed: 0 };
        outputs.push(output);
        node.querySelectorAll("[data-gm-result]").forEach(result => {
            const unit = units.get(Number(result.getAttribute("data-gm-result")));
            if (unit) { unit.result = result; unit.output = output; }
        });
    });
    let version = -1;
    let generation = -1;
    let mode = "original";
    const setText = (node, value) => { if (node.textContent !== value) node.textContent = value; };
    const hasText = value => typeof value === "string" && value.trim().length > 0;
    window.gmTranslation = Object.freeze({
        ready: () => ({ documentId }),
        apply: patch => {
            if (patch.documentId !== documentId || patch.generation < generation ||
                (patch.generation === generation && patch.version < version)) return null;
            if (!["original", "translation", "bilingual"].includes(patch.mode)) return null;
            const modeChanged = mode !== patch.mode;
            const changedOutputs = new Set(modeChanged ? outputs : []);
            units.forEach((unit, id) => {
                const value = patch.values[String(id)];
                const translated = typeof value === "string" ? value : undefined;
                if (!modeChanged && unit.translated === translated) return;
                const { node, original, result, output } = unit;
                // JSON results only fill text slots; safe inline structure remains untouched.
                setText(node, patch.mode === "translation" && translated !== undefined ? translated : original);
                const muted = patch.mode === "bilingual" && translated !== undefined;
                if (node.classList.contains("gm-source") !== muted) node.classList.toggle("gm-source", muted);
                if (result) {
                    setText(result, translated ?? "");
                    output.completed += Number(hasText(translated)) - Number(hasText(unit.translated));
                    changedOutputs.add(output);
                }
                unit.translated = translated;
            });
            changedOutputs.forEach(({ node, completed }) => {
                const hidden = patch.mode !== "bilingual" || completed === 0;
                if (node.hidden !== hidden) node.hidden = hidden;
            });
            mode = patch.mode;
            generation = patch.generation;
            version = patch.version;
            return { documentId, generation, version };
        }
    });
})();
