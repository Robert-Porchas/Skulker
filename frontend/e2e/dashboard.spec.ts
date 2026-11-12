import { expect, test, type Page } from "@playwright/test";
import type { Batch, Dataset, Job } from "../src/lib/types.ts";

const live = Boolean(process.env.SKULKER_LIVE_API_URL);
const mock = "http://127.0.0.1:19081";
const fixture = `Date,Open,High,Low,Close,Volume
2024-01-02,3,3,3,3,100
2024-01-03,2,2,2,2,100
2024-01-04,1,1,1,1,100
2024-01-05,2,2,2,2,100
2024-01-08,10,10,3,3,100
2024-01-09,2,2,2,2,100
`;

test.beforeEach(async ({ request }) => {
  if (!live)
    expect(
      (
        await request.post(mock + "/__test/reset", {
          data: { scenario: "empty" },
        })
      ).ok(),
    ).toBeTruthy();
});

async function upload(
  page: Page,
  csv = fixture,
  symbol = live ? "LIVE" : "TEST",
): Promise<Dataset> {
  const form = page.getByRole("form", { name: "Upload historical dataset" });
  await form.getByLabel("Symbol", { exact: true }).fill(symbol);
  await form.getByLabel("CSV file", { exact: true }).setInputFiles({
    name: "fixture.csv",
    mimeType: "text/csv",
    buffer: Buffer.from(csv),
  });
  const response = page.waitForResponse(
    (reply) =>
      new URL(reply.url()).pathname === "/api/datasets" &&
      reply.request().method() === "POST",
  );
  await form
    .getByRole("button", { name: "Upload dataset", exact: true })
    .click();
  const reply = await response;
  expect(reply.status()).toBe(201);
  return reply.json() as Promise<Dataset>;
}

async function submit(
  page: Page,
  dataset: Dataset,
  shortWindow = 1,
  longWindow = 2,
): Promise<Job> {
  const form = page.getByRole("form", { name: "Single backtest", exact: true });
  await form
    .getByRole("combobox", { name: "Dataset", exact: true })
    .selectOption(dataset.id);
  await form
    .getByRole("spinbutton", { name: /^Short window\b/ })
    .fill(String(shortWindow));
  await form
    .getByRole("spinbutton", { name: /^Long window\b/ })
    .fill(String(longWindow));
  await form.getByLabel("Initial capital", { exact: true }).fill("100");
  await form.getByLabel("Transaction fee", { exact: true }).fill("1");
  const response = page.waitForResponse(
    (reply) =>
      new URL(reply.url()).pathname === "/api/jobs" &&
      reply.request().method() === "POST",
  );
  await form.getByRole("button", { name: "Run backtest", exact: true }).click();
  const reply = await response;
  expect(reply.status()).toBe(202);
  return reply.json() as Promise<Job>;
}

function jobRow(page: Page, id: string) {
  return page
    .getByRole("table", { name: "Recent backtests", exact: true })
    .getByRole("row")
    .filter({
      has: page.getByRole("checkbox", {
        name: `Compare job ${id}`,
        exact: true,
      }),
    });
}

test("empty workspace explains missing data instead of inventing financial results", async ({
  page,
}) => {
  await page.goto("/");
  await expect(
    page.getByText("Research starts with good data.", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Your next idea belongs here.", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Run backtest", exact: true }),
  ).toBeDisabled();
  const results = page.getByRole("region", { name: "Results", exact: true });
  await expect(
    results.getByText("Give your research a curve.", { exact: true }),
  ).toBeVisible();
  await expect(
    results.getByText("Final portfolio value", { exact: true }),
  ).toHaveCount(0);
});

test("backend failures remain visible and retry controls are available", async ({
  page,
  request,
}) => {
  expect(
    (
      await request.post(mock + "/__test/reset", {
        data: { scenario: "error" },
      })
    ).ok(),
  ).toBeTruthy();
  await page.goto("/");
  await expect(
    page.getByText("Connection issue", { exact: true }),
  ).toBeVisible();
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "Historical research service unavailable." })
      .first(),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Retry datasets", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Retry backtests", exact: true }),
  ).toBeVisible();
});

test("invalid strategy windows and money are rejected before job submission", async ({
  page,
  request,
}) => {
  await page.goto("/");
  const dataset = await upload(page);
  const form = page.getByRole("form", { name: "Single backtest", exact: true });
  await form
    .getByRole("combobox", { name: "Dataset", exact: true })
    .selectOption(dataset.id);
  await form.getByRole("spinbutton", { name: /^Short window\b/ }).fill("2");
  await form.getByRole("spinbutton", { name: /^Long window\b/ }).fill("2");
  await form.getByRole("button", { name: "Run backtest", exact: true }).click();
  await expect(form.getByRole("alert")).toContainText(
    "Short window must be smaller than long window.",
  );
  await form.getByRole("spinbutton", { name: /^Short window\b/ }).fill("1");
  await form
    .getByLabel("Initial capital", { exact: true })
    .fill("100.123456789");
  await form.getByRole("button", { name: "Run backtest", exact: true }).click();
  await expect(form.getByRole("alert")).toContainText("8 decimal digits");
  expect((await (await request.get(mock + "/__test/state")).json()).jobs).toBe(
    0,
  );
});

test("CSV validation problems are displayed without adding a dataset", async ({
  page,
  request,
}) => {
  await page.goto("/");
  const form = page.getByRole("form", { name: "Upload historical dataset" });
  await form.getByLabel("Symbol", { exact: true }).fill("TEST");
  await form.getByLabel("CSV file", { exact: true }).setInputFiles({
    name: "invalid.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("Date,Close\n2024-01-02,1\n"),
  });
  await form
    .getByRole("button", { name: "Upload dataset", exact: true })
    .click();
  await expect(form.getByRole("alert")).toContainText("CSV row 1");
  expect(
    (await (await request.get(mock + "/__test/state")).json()).datasets,
  ).toBe(0);
});

test("raw backend decimal tokens beyond 2^53 remain exact through the BFF and result display", async ({
  page,
  request,
}) => {
  expect(
    (
      await request.post(mock + "/__test/reset", {
        data: { scenario: "precision" },
      })
    ).ok(),
  ).toBeTruthy();
  const state = (await (await request.get(mock + "/__test/state")).json()) as {
    precisionId: string;
  };
  await page.goto("/");
  await jobRow(page, state.precisionId)
    .getByRole("button", { name: "View result", exact: true })
    .click();
  const results = page.getByRole("region", { name: "Results", exact: true });
  await expect(
    results
      .getByText("Final portfolio value", { exact: true })
      .locator("..")
      .locator("dd"),
  ).toHaveText("9,007,199,254,740,993.12345678");
  await expect(
    results
      .getByRole("table", { name: /^Trade history/ })
      .getByRole("cell", { name: "9,007,199,254,740,993", exact: true }),
  ).toBeVisible();
  const response = await page.request.get(
    `/api/jobs/${state.precisionId}/result`,
  );
  expect(response.ok()).toBeTruthy();
  const body = await response.json();
  expect(body.finalPortfolioValue).toBe("9007199254740993.12345678");
  expect(body.trades[0].quantity).toBe("9007199254740993");
});

test("mobile workspace keeps content readable without page-wide horizontal overflow", async ({
  page,
  request,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    (
      await request.post(mock + "/__test/reset", {
        data: { scenario: "precision" },
      })
    ).ok(),
  ).toBeTruthy();
  const state = (await (await request.get(mock + "/__test/state")).json()) as {
    precisionId: string;
  };
  await page.goto("/");
  await expect(
    page.getByRole("heading", { level: 1, name: /Backtest workspace/ }),
  ).toBeVisible();
  await jobRow(page, state.precisionId)
    .getByRole("button", { name: "View result", exact: true })
    .click();
  await expect(
    page
      .getByRole("region", { name: "Results", exact: true })
      .getByText("Final portfolio value", { exact: true }),
  ).toBeVisible();
  await expect
    .poll(() =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    )
    .toBe(true);
  const main = await page.getByRole("main").boundingBox();
  expect(main).not.toBeNull();
  expect(main!.width).toBeGreaterThanOrEqual(320);
  const uploadForm = page.getByRole("form", {
    name: "Upload historical dataset",
  });
  await uploadForm.getByLabel("Symbol", { exact: true }).focus();
  await page.keyboard.press("Tab");
  await expect(
    uploadForm.getByLabel("CSV file", { exact: true }),
  ).toBeFocused();
  await page.screenshot({
    path: test.info().outputPath("workspace-mobile.png"),
    fullPage: true,
  });
});

test("upload, execute, inspect trades, compare strategies and complete a parameter sweep @live", async ({
  page,
}) => {
  await page.goto("/");
  const dataset = await upload(page);
  const downloaded = await page.request.get(`/api/datasets/${dataset.id}/csv`);
  expect(downloaded.ok()).toBeTruthy();
  expect(await downloaded.text()).toBe(fixture);
  const first = await submit(page, dataset);
  await expect(
    page.getByRole("checkbox", {
      name: `Compare job ${first.id}`,
      exact: true,
    }),
  ).toBeEnabled();
  await jobRow(page, first.id)
    .getByRole("button", { name: "View result", exact: true })
    .click();
  const results = page.getByRole("region", { name: "Results", exact: true });
  await expect(
    results
      .getByText("Final portfolio value", { exact: true })
      .locator("..")
      .locator("dd"),
  ).toHaveText("27");
  await expect(
    results
      .getByText("Total return", { exact: true })
      .locator("..")
      .locator("dd"),
  ).toHaveText("-73%");
  await expect(
    results
      .getByText("Maximum drawdown", { exact: true })
      .locator("..")
      .locator("dd"),
  ).toHaveText("73%");
  await expect(
    results.getByRole("heading", { name: "Results", exact: true }),
  ).toBeInViewport();
  await expect(results).toBeFocused();
  const trades = results.getByRole("table", { name: /^Trade history/ });
  await expect(trades.getByRole("row")).toHaveCount(2);
  await expect(trades.getByRole("row").nth(1)).toContainText("2024-01-05");
  await expect(trades.getByRole("row").nth(1)).toContainText("2024-01-08");
  await expect(
    trades.getByRole("cell", { name: "10", exact: true }),
  ).toBeVisible();

  const second = await submit(page, dataset, 2, 3);
  await expect(
    page.getByRole("checkbox", {
      name: `Compare job ${second.id}`,
      exact: true,
    }),
  ).toBeEnabled();
  await page
    .getByRole("checkbox", { name: `Compare job ${first.id}`, exact: true })
    .check();
  await page
    .getByRole("checkbox", { name: `Compare job ${second.id}`, exact: true })
    .check();
  await page
    .getByRole("button", { name: "Compare selected", exact: true })
    .click();
  const comparison = page.getByRole("table", {
    name: "Strategy comparison",
    exact: true,
  });
  await expect(comparison.getByRole("row")).toHaveCount(3);
  await expect(
    page.getByRole("heading", { name: "Compare your hypotheses", exact: true }),
  ).toBeInViewport();
  await expect(
    page.getByRole("region", { name: "Compare your hypotheses", exact: true }),
  ).toBeFocused();
  await expect(
    comparison.getByRole("cell", { name: "SMA 1/2", exact: true }),
  ).toBeVisible();
  await expect(
    comparison.getByRole("cell", { name: "SMA 2/3", exact: true }),
  ).toBeVisible();
  await expect(
    comparison.getByRole("cell", { name: "99", exact: true }),
  ).toBeVisible();

  await page
    .getByRole("button", { name: "Parameter sweep", exact: true })
    .click();
  const sweep = page.getByRole("region", {
    name: "Parameter sweep",
    exact: true,
  });
  await sweep
    .getByRole("checkbox", {
      name: `${dataset.symbol} dataset ${dataset.id}`,
      exact: true,
    })
    .check();
  await sweep.getByLabel("Short windows", { exact: true }).fill("1, 2");
  await sweep.getByLabel("Long windows", { exact: true }).fill("3");
  await sweep.getByLabel("Initial capital", { exact: true }).fill("100");
  await sweep.getByLabel("Transaction fee", { exact: true }).fill("1");
  const response = page.waitForResponse(
    (reply) =>
      new URL(reply.url()).pathname === "/api/batches" &&
      reply.request().method() === "POST",
  );
  await sweep.getByRole("button", { name: "Run sweep", exact: true }).click();
  const reply = await response;
  expect(reply.status()).toBe(201);
  const batch = (await reply.json()) as Batch;
  expect(batch.total).toBe(2);
  await expect(
    page.getByRole("progressbar", {
      name: `Sweep ${batch.id} completed tasks`,
      exact: true,
    }),
  ).toHaveAttribute("value", "2");
  await expect(
    page.getByText("2 succeeded · 0 failed · 2 total", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: test.info().outputPath("workspace.png"),
    fullPage: true,
  });
});
