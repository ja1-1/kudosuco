export async function api(path, method = "GET", data = {}) {
  const options = method === "GET" ? {} : {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  };
  const response = await fetch(`/api${path}`, options);
  const result = await response.json();
  if (!response.ok) {
    throw Object.assign(new Error(result.error || "No se pudo completar la operación."), { status: response.status });
  }
  return result;
}
