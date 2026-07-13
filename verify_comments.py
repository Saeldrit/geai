import os

count = 0
examples = []
for root, dirs, files in os.walk('src'):
    dirs[:] = [d for d in dirs if d not in ('build', '.gradle', '.idea')]
    for f in files:
        if f.endswith('.kt') or f.endswith('.java'):
            path = os.path.join(root, f)
            with open(path, 'r', encoding='utf-8') as fh:
                for i, line in enumerate(fh, 1):
                    stripped = line.strip()
                    if stripped.startswith('//'):
                        count += 1
                        if count <= 30:
                            examples.append(f'  {path}:{i}: {stripped[:80]}')
                    elif '//' in stripped and not any(x in stripped for x in ['http://', 'https://', '://']):
                        idx = stripped.find('//')
                        prefix = stripped[:idx].strip()
                        if prefix and not prefix.endswith(':'):
                            count += 1
                            if count <= 30:
                                examples.append(f'  {path}:{i}: {stripped[:80]}')

for e in examples:
    print(e)
print(f'\nTotal remaining single-line comments: {count}')

# Check remaining javadocs
jdoc_count = 0
jdoc_files = set()
jdoc_examples = []
for root, dirs, files in os.walk('src'):
    dirs[:] = [d for d in dirs if d not in ('build', '.gradle', '.idea')]
    for f in files:
        if f.endswith('.kt') or f.endswith('.java'):
            path = os.path.join(root, f)
            with open(path, 'r', encoding='utf-8') as fh:
                in_jdoc = False
                for i, line in enumerate(fh, 1):
                    s = line.strip()
                    if s.startswith('/**'):
                        in_jdoc = True
                        jdoc_count += 1
                        jdoc_files.add(path)
                        if len(jdoc_examples) < 20:
                            jdoc_examples.append(f'  {path}:{i}: {s[:60]}')
                    if '*/' in s and in_jdoc:
                        in_jdoc = False

print(f'\nTotal remaining javadoc blocks: {jdoc_count} across {len(jdoc_files)} files')
for e in jdoc_examples:
    print(e)
