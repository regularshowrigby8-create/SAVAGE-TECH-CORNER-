.PHONY: test gates build verify audit clean

all: test gates audit

test:
	python3 -m unittest discover -s shell/tests
	python3 -m unittest discover -s factory-2/tests
	python3 -m unittest discover -s factory-3/tests
	python3 -m unittest discover -s tools/factory/tests
	python3 -m unittest discover -s factories/tests

coverage:
	python3 -m coverage run -m unittest shell/tests/test_browser_shell.py
	python3 -m coverage report -m --include="shell/soul_browser/browser_shell.py"

gates:
	bash .claude/scripts/gates.sh

audit:
	python3 tools/anti_deception_auditor.py
	python3 tools/schema_validator.py
	python3 tools/reproducible_build_verifier.py

verify: gates audit
