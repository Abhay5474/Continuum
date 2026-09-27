import { describe, expect, it } from "vitest";
import { elapsed, humanMs, toDate, toMillis } from "./time";

describe("toDate", () => {
  it("reads Jackson's epoch seconds, fractional or not, as seconds", () => {
    expect(toMillis(1790491371.011)).toBe(1790491371011);
    expect(toMillis(1790491371)).toBe(1790491371000);
  });

  it("reads epoch milliseconds as milliseconds", () => {
    expect(toMillis(1790491371011)).toBe(1790491371011);
  });

  it("reads ISO strings and numeric strings", () => {
    expect(toMillis("2026-09-27T06:43:00.339Z")).toBe(Date.UTC(2026, 8, 27, 6, 43, 0, 339));
    expect(toMillis("1790491371")).toBe(1790491371000);
  });

  it("gives null for anything that is not a time", () => {
    expect(toDate(null)).toBeNull();
    expect(toDate(undefined)).toBeNull();
    expect(toDate("")).toBeNull();
    expect(toDate("not a date")).toBeNull();
    expect(toDate({})).toBeNull();
  });
});

describe("elapsed and humanMs", () => {
  it("measures between the two shapes the API mixes", () => {
    expect(elapsed(1790491371, "2026-09-27T06:42:59Z")).toBe(8000);
  });

  it("never reports a negative duration", () => {
    expect(elapsed(20, 10)).toBe(0);
  });

  it("formats by magnitude", () => {
    expect(humanMs(420)).toBe("420ms");
    expect(humanMs(8_400)).toBe("8.4s");
    expect(humanMs(125_000)).toBe("2m 5s");
    expect(humanMs(null)).toBe("—");
  });
});
