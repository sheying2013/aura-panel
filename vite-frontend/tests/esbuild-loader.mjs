import { existsSync, statSync } from "node:fs";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { transform } from "esbuild";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(HERE, "../src");
const EXTENSIONS = [".ts", ".tsx", ".js", ".jsx", ".mjs", ".json"];

function isFile(candidate) {
  return existsSync(candidate) && statSync(candidate).isFile();
}

function resolveFile(base) {
  if (isFile(base)) return base;

  for (const ext of EXTENSIONS) {
    if (isFile(base + ext)) return base + ext;
  }

  for (const ext of EXTENSIONS) {
    const candidate = path.join(base, "index" + ext);

    if (isFile(candidate)) return candidate;
  }

  return null;
}

export async function resolve(specifier, context, nextResolve) {
  let base = null;

  if (specifier.startsWith("@/")) {
    base = path.join(SRC, specifier.slice(2));
  } else if (specifier.startsWith("file:")) {
    base = fileURLToPath(new URL(specifier));
  } else if (
    (specifier.startsWith("./") ||
      specifier.startsWith("../") ||
      path.isAbsolute(specifier)) &&
    context.parentURL &&
    context.parentURL.startsWith("file:")
  ) {
    base = path.resolve(
      path.dirname(fileURLToPath(context.parentURL)),
      specifier,
    );
  }

  if (base) {
    const file = resolveFile(base);

    if (file) return { url: pathToFileURL(file).href, shortCircuit: true };
  }

  return nextResolve(specifier, context);
}

export async function load(url, context, nextLoad) {
  if (url.startsWith("file:")) {
    const file = fileURLToPath(new URL(url));

    if (file.endsWith(".ts") || file.endsWith(".tsx")) {
      const source = await readFile(file, "utf8");
      const result = await transform(source, {
        loader: file.endsWith(".tsx") ? "tsx" : "ts",
        format: "esm",
        target: "es2022",
        jsx: "automatic",
        sourcefile: file,
        sourcemap: "inline",
        define: {
          "import.meta.env.VITE_API_BASE": '""',
        },
      });

      return { format: "module", source: result.code, shortCircuit: true };
    }
  }

  return nextLoad(url, context);
}
