import sys
src, out = sys.argv[1], sys.argv[2]
lines = open(src, encoding='utf-8').read().splitlines()
edges = []  # (child, parent)
nodes = set()
stack = []  # (level, name)
desc = set()
section = None
for raw in lines:
    if not raw.strip(): continue
    indent = len(raw) - len(raw.lstrip(' '))
    level = indent // 4
    text = raw.strip()
    if level == 0:
        section = text; stack = []; continue
    if section not in ("Genres", "Scenes & Movements"): continue
    is_leaf = text.endswith("::genre")
    name = text[:-7] if is_leaf else text
    if "::" in name: continue
    while stack and stack[-1][0] >= level: stack.pop()
    parent = stack[-1][1] if stack else None
    if section == "Scenes & Movements" and parent is None:
        parent = "Scenes & Movements"
    if parent == "Descriptor": desc.add(name)
    if parent in ("Uncategorised", "Descriptor"): parent = None
    if name in ("Uncategorised", "Descriptor") and section == "Genres":
        stack.append((level, name)); continue
    if not is_leaf:
        stack.append((level, name))
    if parent is not None and name == parent:  # "X::genre" logo abaixo do grupo X
        continue
    nodes.add(name)
    edges.append((name, parent or ""))
nodes.add("Scenes & Movements")
edges.append(("Scenes & Movements", ""))
has_parent = {c for c,p in edges if p}
edges = [(c,p) for c,p in edges if not (p=="" and c in desc and c in has_parent)]
seen=set(); uniq=[]
for e in edges:
    if e not in seen: seen.add(e); uniq.append(e)
with open(out,'w',encoding='utf-8') as f:
    f.write("# Fork pessoal: árvore de gêneros do RateYourMusic (cópia de github.com/FlakyBlueJay/mb-rym-hierarchy, atualizada em 2026-10-26).\n")
    f.write("# Uma linha por ligação: gênero<TAB>pai (pai vazio = primeiro nível). Um gênero pode ter mais de um pai.\n")
    for c,p in uniq: f.write(f"{c}\t{p}\n")
tops = sorted(c for c,p in uniq if p=="")
multi = {}
for c,p in uniq: multi.setdefault(c,set()).add(p)
print("nós", len(nodes), "ligações", len(uniq), "primeiro nível", len(tops), "com mais de um pai", sum(1 for v in multi.values() if len(v)>1))
print(tops)
