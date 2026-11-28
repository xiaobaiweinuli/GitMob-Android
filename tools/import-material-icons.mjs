// Maintenance only: npm ci --prefix tools && npm --prefix tools run import-icons
// Icon attribution is retained in app/src/main/assets/material-file-icons.LICENSE.txt.
import { readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';
import { Resvg } from '@resvg/resvg-js';
import { DOMParser, XMLSerializer } from '@xmldom/xmldom';
import CanvasKitInit from 'canvaskit-wasm';

const require = createRequire(import.meta.url);
const CanvasKit = await CanvasKitInit({
    wasmBinary: await readFile(require.resolve('canvaskit-wasm/bin/canvaskit.wasm')),
});

const parse = (source) => new DOMParser({
    onError: (level, message) => { throw new Error(message); },
}).parseFromString(source, 'image/svg+xml');

function normalizeSvg(source) {
    const original = parse(source);
    const hasStyles = Array.from(original.getElementsByTagName('*'))
        .some(element => element.hasAttribute('stroke') || element.hasAttribute('style'));
    if (original.getElementsByTagName('svg').length === 1 && !hasStyles) return source;

    const doc = parse(new Resvg(source, { font: { loadSystemFonts: false } }).toString());
    for (const element of Array.from(doc.getElementsByTagName('path'))) {
        const color = element.getAttribute('stroke');
        if (!color || color === 'none') continue;
        if (element.hasAttribute('stroke-dasharray') || color.startsWith('url(') || element.hasAttribute('paint-order')) {
            throw new Error('Unsupported stroke attributes');
        }
        // Outline in local coordinates before the parent transform scales the stroke.
        const path = CanvasKit.Path.MakeFromSVGString(element.getAttribute('d'));
        if (!path) throw new Error('Cannot parse path');
        let outlined;
        try {
            outlined = path.makeStroked({
                width: Number(element.getAttribute('stroke-width') || 1),
                miter_limit: Number(element.getAttribute('stroke-miterlimit') || 4),
                cap: CanvasKit.StrokeCap[(element.getAttribute('stroke-linecap') || 'butt').replace(/^./, c => c.toUpperCase())],
                join: CanvasKit.StrokeJoin[(element.getAttribute('stroke-linejoin') || 'miter').replace(/^./, c => c.toUpperCase())],
                precision: 100,
            });
            if (!outlined) throw new Error('Cannot outline path');
            const outline = element.cloneNode(false);
            for (const attr of Array.from(outline.attributes)) {
                if (attr.name.startsWith('stroke') || attr.name.startsWith('fill') || attr.name === 'clip-rule') {
                    outline.removeAttribute(attr.name);
                }
            }
            outline.setAttribute('d', outlined.toSVGString());
            outline.setAttribute('fill', color);
            if (element.hasAttribute('stroke-opacity')) {
                outline.setAttribute('fill-opacity', element.getAttribute('stroke-opacity'));
            }
            element.parentNode.insertBefore(outline, element.nextSibling);
            for (const attr of Array.from(element.attributes)) {
                if (attr.name.startsWith('stroke')) element.removeAttribute(attr.name);
            }
            if (element.getAttribute('fill') === 'none') element.parentNode.removeChild(element);
        } finally {
            outlined?.delete();
            path.delete();
        }
    }
    return new XMLSerializer().serializeToString(doc).trim();
}

const upstream = dirname(require.resolve('material-icon-theme/package.json'));
const { version } = require('material-icon-theme/package.json');
if (version !== require('./package.json').devDependencies['material-icon-theme']) {
    throw new Error('Installed material-icon-theme version does not match package.json');
}
const { generateManifest } = require('material-icon-theme');
const manifest = generateManifest({ activeIconPack: 'react' });
const fields = ['fileNames', 'fileExtensions', 'folderNames', 'languageIds'];
const sorted = (values) => Object.fromEntries(Object.keys(values).sort().map((key) => [key, values[key]]));
const associations = Object.fromEntries(fields.map((field) => [field, sorted(manifest[field] ?? {})]));
const lightVariants = {};
for (const field of fields) {
    for (const [key, lightIcon] of Object.entries(manifest.light?.[field] ?? {})) {
        const icon = associations[field][key];
        if (!icon || (lightVariants[icon] && lightVariants[icon] !== lightIcon)) {
            throw new Error('Inconsistent light variant: ' + field + '/' + key);
        }
        lightVariants[icon] = lightIcon;
    }
}
const requiredIcons = new Set([
    'folder-git',
    ...fields.flatMap((field) => Object.values(associations[field])),
    ...Object.values(lightVariants),
]);
// Generic files and folders use the application's theme-aware fallback icons.
requiredIcons.delete('file');
requiredIcons.delete('folder');
const icons = {};
for (const icon of requiredIcons) {
    const definition = manifest.iconDefinitions[icon];
    if (!definition?.iconPath) throw new Error('Missing icon: ' + icon);
    icons[icon] = (await readFile(resolve(upstream, 'dist', definition.iconPath), 'utf8')).trim();
}
const catalogPath = new URL('./material-file-icons.json', import.meta.url);
const catalog = JSON.parse(await readFile(catalogPath, 'utf8'));
icons['folder-symlink'] = catalog.icons['folder-symlink'];
if (!icons['folder-symlink']) throw new Error('Missing folder-symlink in the existing catalog');
for (const [name, source] of Object.entries(icons)) {
    try {
        icons[name] = normalizeSvg(source);
    } catch (cause) {
        throw new Error('Cannot normalize icon: ' + name, { cause });
    }
}
await writeFile(catalogPath, JSON.stringify({
    version,
    ...associations,
    lightVariants: sorted(lightVariants),
    icons: sorted(icons),
}, null, 2) + '\n');
console.log('Imported material-icon-theme@' + version + ': ' + Object.keys(icons).length + ' SVG icons');
