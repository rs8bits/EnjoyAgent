"""Structured document operations. Files and user code remain in the outer container boundary."""
import csv
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile

OUTPUT = Path('/output')
MAX_PAGES = 500
MAX_ROWS = 200000
MAX_COLUMNS = 500


def integer(value, default, low, high):
    result = int(default if value is None else value)
    if result < low or result > high:
        raise ValueError(f'Value must be between {low} and {high}')
    return result


def pdf_reader(path):
    from pypdf import PdfReader
    reader = PdfReader(path)
    if reader.is_encrypted:
        raise ValueError('Encrypted PDF is not supported; upload a decrypted copy')
    if len(reader.pages) > MAX_PAGES:
        raise ValueError('PDF exceeds the 500-page limit')
    return reader


def selected_pages(args, count, maximum=50):
    values = args.get('pages') or [1]
    if not isinstance(values, list) or not 1 <= len(values) <= maximum:
        raise ValueError('Invalid page selection')
    pages = [integer(page, 1, 1, count) for page in values]
    if len(set(pages)) != len(pages):
        raise ValueError('Duplicate page selection')
    return pages


def pdf_operation(operation, paths, args):
    from pypdf import PdfWriter
    if not paths:
        raise ValueError('PDF input is required')
    if operation == 'PDF_MERGE':
        writer = PdfWriter()
        total = 0
        for path in paths:
            reader = pdf_reader(path)
            total += len(reader.pages)
            if total > MAX_PAGES:
                raise ValueError('Merged PDF exceeds the page limit')
            writer.append(reader)
        writer.write(OUTPUT / 'merged.pdf')
        return {'pageCount': total, 'inputCount': len(paths)}
    reader = pdf_reader(paths[0])
    count = len(reader.pages)
    if operation == 'PDF_EXTRACT':
        pages = args.get('pages') or list(range(1, count + 1))
        pages = selected_pages({'pages': pages}, count, MAX_PAGES)
        chunks = []
        size = 0
        for page in pages:
            text = reader.pages[page - 1].extract_text() or ''
            size += len(text)
            if size > 2000000:
                raise ValueError('Extracted text exceeds the limit')
            chunks.append(f'--- Page {page} ---\n{text}')
        text = '\n\n'.join(chunks)
        (OUTPUT / 'extracted.txt').write_text(text, encoding='utf-8')
        return {'pageCount': count, 'selectedPages': pages, 'characters': len(text), 'excerpt': text[:3000]}
    if operation == 'PDF_SPLIT':
        ranges = args.get('ranges')
        if ranges is None:
            pages = selected_pages({'pages': args.get('pages') or list(range(1, count + 1))}, count, 99)
            ranges = [[page, page] for page in pages]
        if not isinstance(ranges, list) or not 1 <= len(ranges) <= 99:
            raise ValueError('At most 99 split ranges are supported')
        for index, value in enumerate(ranges, 1):
            if not isinstance(value, list) or len(value) != 2:
                raise ValueError('Each range must be [firstPage,lastPage]')
            first, last = (integer(page, 1, 1, count) for page in value)
            if first > last:
                raise ValueError('Invalid page range')
            writer = PdfWriter()
            for page in range(first - 1, last):
                writer.add_page(reader.pages[page])
            writer.write(OUTPUT / f'part-{index:03}.pdf')
        return {'pageCount': count, 'parts': len(ranges)}
    if operation == 'PDF_RENDER':
        import pypdfium2
        pages = selected_pages(args, count)
        dpi = integer(args.get('dpi'), 120, 36, 200)
        document = pypdfium2.PdfDocument(paths[0])
        pixels = 0
        try:
            for number in pages:
                page = document[number - 1]
                width, height = page.get_size()
                pixels += int(width * dpi / 72) * int(height * dpi / 72)
                if pixels > 50000000:
                    raise ValueError('Render exceeds the pixel budget')
                bitmap = page.render(scale=dpi / 72)
                try:
                    bitmap.to_pil().save(OUTPUT / f'page-{number:03}.png')
                finally:
                    bitmap.close()
                    page.close()
        finally:
            document.close()
        return {'pageCount': count, 'renderedPages': pages, 'dpi': dpi}
    raise ValueError('Unknown PDF operation')


def office_convert(paths, args):
    if len(paths) != 1 or paths[0].suffix.lower() not in {'.doc', '.docx', '.xls', '.xlsx', '.ppt', '.pptx', '.odt', '.ods', '.odp'}:
        raise ValueError('Select one supported Office file')
    target = args.get('format', 'pdf')
    if target not in {'pdf', 'docx', 'xlsx', 'csv'}:
        raise ValueError('Unsupported conversion format')
    profile = Path('/tmp') / ('lo-' + uuid.uuid4().hex)
    profile.mkdir()
    # Very high macro security; no trusted directories/certificates are configured.
    (profile / 'user').mkdir()
    (profile / 'user/registrymodifications.xcu').write_text('''<?xml version="1.0" encoding="UTF-8"?>
<oor:items xmlns:oor="http://openoffice.org/2001/registry">
<item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item>
<item oor:path="/org.openoffice.Office.Common/Load"><prop oor:name="UpdateLinks" oor:op="fuse"><value>false</value></prop></item>
</oor:items>''', encoding='utf-8')
    try:
        process = subprocess.run(['soffice', '-env:UserInstallation=' + profile.as_uri(), '--headless', '--norestore',
                                  '--convert-to', target, '--outdir', str(OUTPUT), str(paths[0])],
                                 capture_output=True, text=True, timeout=270, check=False)
        result = OUTPUT / (paths[0].stem + '.' + target)
        if process.returncode != 0 or not result.is_file() or result.stat().st_size == 0:
            raise ValueError('Office conversion did not produce a valid file')
        if target == 'pdf':
            pdf_reader(result)
        return {'format': target, 'fileName': result.name, 'sizeBytes': result.stat().st_size}
    finally:
        shutil.rmtree(profile, ignore_errors=True)


def read_table(path, args):
    import pandas as pd
    extension = path.suffix.lower()
    if extension in {'.csv', '.tsv', '.txt'}:
        encoding = args.get('encoding', 'utf-8-sig')
        if encoding not in {'utf-8', 'utf-8-sig', 'gb18030', 'utf-16'}:
            raise ValueError('Unsupported text encoding')
        separator = args.get('separator', '\t' if extension == '.tsv' else ',')
        if not isinstance(separator, str) or len(separator) != 1:
            raise ValueError('Separator must be one character')
        data = pd.read_csv(path, encoding=encoding, sep=separator, nrows=MAX_ROWS + 1)
    elif extension in {'.xlsx', '.xls'}:
        if extension == '.xlsx':
            with zipfile.ZipFile(path) as archive:
                if sum(entry.file_size for entry in archive.infolist()) > 200 * 1024 * 1024:
                    raise ValueError('Spreadsheet expands beyond the size limit')
        data = pd.read_excel(path, sheet_name=args.get('sheet', 0), nrows=MAX_ROWS + 1)
    else:
        raise ValueError('Expected CSV, TSV, XLS or XLSX input')
    if len(data) > MAX_ROWS or len(data.columns) > MAX_COLUMNS:
        raise ValueError('Table exceeds row/column limits')
    data.columns = [str(column) for column in data.columns]
    if len(set(data.columns)) != len(data.columns):
        raise ValueError('Column names must be unique')
    return data


def table_preview(data):
    return json.loads(data.head(20).to_json(orient='records', date_format='iso', force_ascii=False))


def export_table(data):
    # Neutralize formula-bearing strings in deliverable spreadsheets/CSV, without changing numbers.
    safe = data.copy()
    def cell(value):
        return "'" + value if isinstance(value, str) and value.lstrip().startswith(('=', '+', '-', '@', '\t', '\r')) else value
    safe = safe.map(cell)
    safe.columns = [cell(str(column)) for column in safe.columns]
    safe.to_csv(OUTPUT / 'result.csv', index=False, encoding='utf-8-sig')
    with __import__('pandas').ExcelWriter(OUTPUT / 'result.xlsx', engine='xlsxwriter', engine_kwargs={'options': {'strings_to_formulas': False, 'strings_to_urls': False}}) as writer:
        safe.to_excel(writer, index=False, sheet_name='Result')


def table_operation(operation, paths, args):
    import pandas as pd
    if not paths:
        raise ValueError('Table input is required')
    data = read_table(paths[0], args)
    if operation == 'TABLE_INSPECT':
        summary = {'rowCount': len(data), 'columns': [{'name': name, 'type': str(dtype), 'missing': int(data[name].isna().sum())}
                                                     for name, dtype in data.dtypes.items()], 'preview': table_preview(data)}
        if paths[0].suffix.lower() in {'.xls', '.xlsx'}:
            with pd.ExcelFile(paths[0]) as workbook:
                summary['sheets'] = workbook.sheet_names[:100]
        return summary
    filters = args.get('filters') or []
    if not isinstance(filters, list) or len(filters) > 20:
        raise ValueError('Too many filters')
    for rule in filters:
        column, operator, value = rule['column'], rule['operator'], rule.get('value')
        if column not in data:
            raise ValueError('Unknown filter column')
        series = data[column]
        if operator == 'eq': mask = series == value
        elif operator == 'ne': mask = series != value
        elif operator == 'gt': mask = series > value
        elif operator == 'gte': mask = series >= value
        elif operator == 'lt': mask = series < value
        elif operator == 'lte': mask = series <= value
        elif operator == 'contains': mask = series.astype(str).str.contains(str(value), regex=False, na=False)
        else: raise ValueError('Unsupported filter operator')
        data = data[mask]
    groups, aggregates = args.get('groupBy') or [], args.get('aggregations') or {}
    if not isinstance(groups, list) or len(groups) > 8 or any(column not in data for column in groups):
        raise ValueError('Invalid groupBy columns')
    if not isinstance(aggregates, dict) or any(column not in data or op not in {'sum', 'mean', 'min', 'max', 'count', 'nunique'} for column, op in aggregates.items()):
        raise ValueError('Invalid aggregations')
    if aggregates:
        result = data.groupby(groups, dropna=False).agg(aggregates).reset_index() if groups else data.agg(aggregates).to_frame('value').reset_index(names='column')
    else:
        result = data if filters else data.describe(include='all').reset_index(names='statistic')
    sort = args.get('sortBy') or []
    if sort:
        if not isinstance(sort, list) or any(column not in result for column in sort):
            raise ValueError('Invalid sort columns')
        result = result.sort_values(sort, ascending=bool(args.get('ascending', True)))
    result = result.head(integer(args.get('limit'), MAX_ROWS, 1, MAX_ROWS))
    export_table(result)
    return {'inputRows': len(data), 'resultRows': len(result), 'preview': table_preview(result)}


def sql_execute(paths, args, code):
    import duckdb
    if not paths or not code.strip():
        raise ValueError('SQL and input files are required')
    database = duckdb.connect(':memory:', config={'enable_external_access': 'false', 'allow_unsigned_extensions': 'false',
        'autoinstall_known_extensions': 'false', 'autoload_known_extensions': 'false', 'memory_limit': '256MB', 'threads': '1'})
    try:
        for number, path in enumerate(paths, 1):
            database.register(f'input_{number}', read_table(path, args))
        database.execute('SET lock_configuration=true')
        database.execute(code)
        result = database.fetch_df_chunk(256)
        if len(result) > MAX_ROWS:
            raise ValueError('SQL result exceeds row limit; add LIMIT')
        # Fetching one more row detects results larger than our exported prefix.
        if database.fetchone() is not None:
            raise ValueError('SQL result exceeds export limit; add LIMIT')
        export_table(result)
        return {'tables': [f'input_{number}' for number in range(1, len(paths) + 1)], 'resultRows': len(result), 'preview': table_preview(result)}
    finally:
        database.close()


def execute(operation):
    spec = json.loads(Path('/code/spec.json').read_text(encoding='utf-8'))
    paths = [Path('/input') / item['name'] for item in spec.get('inputs', [])]
    args = spec.get('arguments') or {}
    if not isinstance(args, dict):
        raise ValueError('Arguments must be an object')
    if operation.startswith('PDF_'): summary = pdf_operation(operation, paths, args)
    elif operation == 'OFFICE_CONVERT': summary = office_convert(paths, args)
    elif operation in {'TABLE_INSPECT', 'TABLE_ANALYZE'}: summary = table_operation(operation, paths, args)
    elif operation == 'SQL': summary = sql_execute(paths, args, Path('/code/main.py').read_text())
    else: raise ValueError('Unsupported operation')
    (OUTPUT / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, default=str, indent=2), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, default=str)[:6000])
