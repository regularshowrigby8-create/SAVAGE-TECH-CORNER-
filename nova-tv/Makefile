.PHONY: help install test lint scripts doctor clean all

PY := python3
DIR := python

help:
	@echo "make test      run the Python test suite"
	@echo "make scripts   execute every script and report failures"
	@echo "make doctor    environment readiness"
	@echo "make inventory list every registered tool"
	@echo "make clean     remove caches and artifacts"

test:
	cd $(DIR) && $(PY) -m unittest discover -s tests -t . -v

scripts:
	@cd $(DIR) && ok=0; bad=0; \
	for f in scripts/*.py; do \
	  if timeout 60 $(PY) "$$f" --json 2>&1 | grep -q '"ok": true'; then ok=$$((ok+1)); \
	  else bad=$$((bad+1)); echo "  FAILED $$f"; fi; \
	done; \
	echo "scripts: $$ok ok, $$bad failed"

lint:
	cd $(DIR) && $(PY) scripts/qa_lint.py

doctor:
	cd $(DIR) && $(PY) scripts/ops_doctor.py

inventory:
	cd $(DIR) && $(PY) scripts/ops_inventory.py

clean:
	rm -rf .nova-cache .nova-snapshots python/**/__pycache__ reports
	find . -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true

all: test scripts doctor
