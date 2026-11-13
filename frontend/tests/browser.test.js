import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { test } from "node:test";
import { chromium } from "playwright";

const base = process.env.KUDOS_TEST_URL || "http://127.0.0.1:5177";
const screenshotDir = process.env.KUDOS_TEST_SCREENSHOTS;

async function screenshot(page, name) {
  if (!screenshotDir) return;
  await mkdir(screenshotDir, { recursive: true });
  await page.screenshot({ path: `${screenshotDir}/${name}.png`, fullPage: true });
}

async function login(page, role) {
  await page.goto(base);
  await page.getByLabel("Correo institucional").fill(`${role}@uco.edu.co`);
  await page.getByLabel("Contraseña").fill("1234");
  await page.getByRole("button", { name: "Iniciar sesión", exact: true }).click();
  await page.getByRole("heading", { name: "Comunidad", exact: true }).waitFor();
}

test("Una respuesta vacía de la API muestra cómo reintentar", async (t) => {
  const browser = await chromium.launch({ executablePath: process.env.KUDOS_CHROME || undefined });
  t.after(() => browser.close());
  const page = await browser.newPage();
  const patron = "**/api/options";
  await page.route(patron, (route) => route.fulfill({ status: 500, body: "" }));

  await page.goto(base);
  await page.getByText("La API de UCO Kudos no está disponible. Intenta de nuevo en unos segundos.", { exact: true })
    .waitFor({ timeout: 5000 });

  await page.unroute(patron);
  await page.getByRole("button", { name: "Reintentar", exact: true }).click();
  await page.getByRole("heading", { name: "Iniciar sesión", exact: true }).waitFor();
});

test("Comunidad, réplica, valoración, reporte, veto y auditoría en Chromium", async (t) => {
  const browser = await chromium.launch({ executablePath: process.env.KUDOS_CHROME || undefined });
  t.after(() => browser.close());
  const student = await browser.newPage({ viewport: { width: 1440, height: 920 } });
  const errors = [];
  function captureErrors(page) {
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
      const text = message.text();
      const expected = text === "Failed to load resource: the server responded with a status of 401 (Unauthorized)";
      if (message.type() === "error" && !expected) errors.push(text);
    });
  }
  captureErrors(student);
  await student.goto(base);
  await student.getByRole("heading", { name: "Iniciar sesión" }).waitFor();
  await screenshot(student, "01-ingreso");
  await login(student, "estudiante");
  await screenshot(student, "02-comunidad");
  assert.equal(await student.getByRole("link", { name: "Moderación" }).count(), 0);
  await student.getByRole("button", { name: "Dar un Kudo" }).click();
  const modal = student.getByRole("dialog");
  await modal.getByRole("option", { name: "Daniela Ríos" }).waitFor({ state: "attached" });
  await modal.getByLabel("Destinatario", { exact: true }).selectOption({ label: "Daniela Ríos" });
  await modal.getByLabel("Categoría", { exact: true }).selectOption({ label: "Colaboración" });
  const body = 'Gracias por compartir <img src="x" onerror="alert(1)"> como ejemplo.';
  await modal.getByLabel("¿Qué quieres reconocer?").fill(body);
  await modal.getByRole("button", { name: "Publicar Kudo" }).click();
  const kudo = student.locator(".detail-kudo");
  await kudo.getByText(body, { exact: true }).waitFor();
  assert.equal(await student.locator('img[src="x"]').count(), 0);
  await kudo.getByRole("button", { name: "Responder", exact: true }).click();
  await modal.getByLabel("Tu mensaje").fill("Réplica de integración");
  await modal.getByRole("button", { name: "Publicar réplica" }).click();
  const reply = student.locator("article.reply", { hasText: "Réplica de integración" });
  await reply.getByRole("button", { name: "Valorar", exact: true }).click();
  await modal.getByLabel("Estrellas").selectOption("4");
  await modal.getByRole("button", { name: "Guardar valoración" }).click();
  await reply.getByText("★ 4 · 1 valoraciones").waitFor();
  await reply.getByRole("button", { name: "Ver valoraciones", exact: true }).click();
  await modal.getByRole("cell", { name: "4 ★", exact: true }).waitFor();
  await modal.getByRole("button", { name: "Cerrar", exact: true }).click();
  await reply.getByRole("button", { name: "Reportar", exact: true }).click();
  await modal.getByLabel("Motivo", { exact: true }).selectOption("SPAM");
  await modal.getByRole("button", { name: "Enviar reporte" }).click();
  await student.getByText("Reporte recibido.", { exact: true }).waitFor();
  await screenshot(student, "03-conversacion");

  const rector = await browser.newPage({ viewport: { width: 1440, height: 920 } });
  captureErrors(rector);
  await login(rector, "rector");
  await rector.getByRole("link", { name: "Moderación" }).click();
  const report = rector.locator("section.panel").filter({ hasText: "Réplica #" }).first();
  await report.getByRole("button", { name: "Iniciar revisión" }).click();
  await report.getByLabel("Decisión").selectOption("accept");
  await report.getByLabel("Justificación").fill("Decisión de prueba reproducible");
  await report.getByRole("button", { name: "Registrar decisión" }).click();
  await report.getByText("Aceptado", { exact: true }).waitFor();
  await rector.getByRole("link", { name: "vetos", exact: true }).click();
  await rector.getByText("Decisión de prueba reproducible", { exact: true }).first().waitFor();
  await screenshot(rector, "04-vetos");
  await rector.getByRole("link", { name: "Auditoría" }).click();
  await rector.getByText("ReplicaVetada", { exact: true }).first().waitFor();
  await student.reload();
  await student.getByText("Sin réplicas.").waitFor();
  assert.equal(await student.locator("article.reply").count(), 0);
  assert.deepEqual(errors, []);
});

test("Menú de estudiante y navegación en pantalla móvil", async (t) => {
  const browser = await chromium.launch({ executablePath: process.env.KUDOS_CHROME || undefined });
  t.after(() => browser.close());
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true });
  await login(page, "estudiante");
  const dimensions = await page.evaluate(() => [document.documentElement.scrollWidth, window.innerWidth]);
  assert.ok(dimensions[0] <= dimensions[1]);
  await page.getByRole("link", { name: "Mis reconocimientos" }).click();
  await page.getByRole("heading", { name: "Mis reconocimientos" }).waitFor();
  await page.getByRole("link", { name: "Mi perfil" }).click();
  await page.getByRole("heading", { name: "Valentina Soto" }).waitFor();
  await screenshot(page, "05-perfil-movil");
  await page.getByRole("link", { name: "Organización" }).click();
  await page.getByRole("row", { name: /Facultad de Ingenierías.*Tomás Montoya/ }).waitFor();
  await page.getByRole("row", { name: /Ingeniería de Sistemas.*Sara Gil/ }).waitFor();
});
