import type { DemoSpec } from "../system/demo";
import { LIVE } from "./live";
import { SAMPLE } from "./sample";

/** Every feature's demo, by route. Loaded on first use, so it costs the console nothing until then. */
export const DEMOS: Record<string, DemoSpec> = { ...SAMPLE, ...LIVE };
