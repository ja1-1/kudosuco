export async function api(path, method = "GET", data = {}) {
  const options = method === "GET" ? {} : {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  };
  const response = await fetch(`/api${path}`, options);
  let result;
  try {
    result = await response.json();
  } catch {
    throw Object.assign(new Error("La API de UCO Kudos no está disponible. Intenta de nuevo en unos segundos."), {
      status: response.status,
    });
  }
  if (!response.ok) {
    throw Object.assign(new Error(result?.error || "No se pudo completar la operación."), { status: response.status });
  }
  return result;
}
