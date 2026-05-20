"""
Queries Wikidata SPARQL to find video game QIDs matching our RAWG game names.
Outputs sameAs_wikidata.ttl with owl:sameAs triples.

Results are cached in src/wikidata_cache.json — safe to re-run if interrupted.
Runtime: ~15 seconds for 5000 games (10 batches x 1.2s delay).

Run from anywhere:
    python src/link_to_wikidata.py
"""

import csv, time, json, os, requests
from pathlib import Path

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.join(SCRIPT_DIR, '..')

WIKIDATA_SPARQL = "https://query.wikidata.org/sparql"
HEADERS = {
    "User-Agent": "VideoGameOntologyProject/1.0 (university project)",
    "Accept": "application/json"
}
BATCH_SIZE  = 500
DELAY       = 1.2
GAME_LIMIT  = 5000  # must match Main.java GAME_LIMIT

GAMES_CSV   = os.path.join(PROJECT_DIR, 'Dataset', 'processed', 'games_flat.csv')
OUTPUT_TTL  = os.path.join(PROJECT_DIR, 'sameAs_wikidata.ttl')
CACHE_FILE  = os.path.join(SCRIPT_DIR, 'wikidata_cache.json')


def escape(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')


def query_batch(name_to_id: dict) -> dict:
    """Returns {game_name: wikidata_uri} for games found in Wikidata."""
    values = " ".join(f'"{escape(n)}"@en' for n in name_to_id)
    sparql = f"""
SELECT DISTINCT ?item ?label WHERE {{
  VALUES ?label {{ {values} }}
  ?item wdt:P31 wd:Q7889 ;
        rdfs:label ?label .
}}
"""
    try:
        r = requests.get(WIKIDATA_SPARQL,
                         params={"query": sparql, "format": "json"},
                         headers=HEADERS, timeout=30)
        r.raise_for_status()
        results = {}
        for b in r.json()["results"]["bindings"]:
            label = b["label"]["value"]
            uri   = b["item"]["value"]
            if label in name_to_id and label not in results:
                results[label] = uri
        return results
    except Exception as e:
        print(f"  Request error: {e}")
        return {}


# ── Load games ────────────────────────────────────────────────────────────────

with open(GAMES_CSV, encoding="utf-8") as f:
    games = list(csv.DictReader(f))[:GAME_LIMIT]

print(f"Loaded {len(games)} games (limit={GAME_LIMIT})")

# ── Load or create cache ──────────────────────────────────────────────────────

cache = {}
if Path(CACHE_FILE).exists():
    with open(CACHE_FILE, encoding="utf-8") as f:
        cache = json.load(f)
    print(f"Resuming from cache ({len(cache)} entries already fetched)")

# ── Query Wikidata in batches ─────────────────────────────────────────────────

to_process    = [g for g in games if g["name"] not in cache]
total_batches = (len(to_process) + BATCH_SIZE - 1) // BATCH_SIZE
print(f"{len(to_process)} games to query ({total_batches} batches)\n")

for i in range(0, len(to_process), BATCH_SIZE):
    batch      = to_process[i : i + BATCH_SIZE]
    name_to_id = {g["name"]: g["id"] for g in batch}
    batch_num  = i // BATCH_SIZE + 1

    print(f"[{batch_num}/{total_batches}] querying {len(batch)} games...", end=" ", flush=True)
    found = query_batch(name_to_id)

    for name in name_to_id:
        cache[name] = found.get(name)

    print(f"{len(found)} matched")

    with open(CACHE_FILE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=2)

    time.sleep(DELAY)

# ── Write TTL output ──────────────────────────────────────────────────────────

print(f"\nWriting {OUTPUT_TTL}...")
lines = [
    "@prefix :   <http://www.semanticweb.org/videogame-ontology#> .",
    "@prefix owl: <http://www.w3.org/2002/07/owl#> .\n"
]

linked = 0
for game in games:
    uri = cache.get(game["name"])
    if uri:
        lines.append(f':game_{game["id"]} owl:sameAs <{uri}> .')
        linked += 1

with open(OUTPUT_TTL, "w", encoding="utf-8") as f:
    f.write("\n".join(lines) + "\n")

print(f"Done: {linked}/{len(games)} games linked ({linked/len(games)*100:.1f}%)")
print(f"Output: {OUTPUT_TTL}")
