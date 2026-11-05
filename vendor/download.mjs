import { createWriteStream } from 'node:fs';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
const [url, destination] = process.argv.slice(2);
const response = await fetch(url);
if (!response.ok) throw new Error(`Download failed: ${response.status}`);
await pipeline(Readable.fromWeb(response.body), createWriteStream(destination));
console.log(`Downloaded ${destination}`);
