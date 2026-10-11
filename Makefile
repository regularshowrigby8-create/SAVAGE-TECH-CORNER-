.PHONY: test gates build verify audit clean conveyor-belt

all: test gates audit conveyor-belt

test:
	python3 -m unittest discover -s shell/tests
	python3 -m unittest discover -s factory-2/tests
	python3 -m unittest discover -s factory-3/tests
	python3 -m unittest discover -s tools/factory/tests
	python3 -m unittest discover -s factories/tests
	python3 factory-2/app/tests/test_app.py
	python3 factories/factory-2/app/tests/test_app.py
	python3 factories/factory-2/tests/test_model_debugger.py

coverage:
	python3 -m coverage run -m unittest shell/tests/test_browser_shell.py
	python3 -m coverage report -m --include="shell/soul_browser/browser_shell.py"

gates:
	bash .claude/scripts/gates.sh

audit:
	python3 tools/anti_deception_auditor.py
	python3 tools/schema_validator.py
	python3 tools/reproducible_build_verifier.py

conveyor-belt:
	node scripts/conveyor_belt.js

verify: gates audit conveyor-belt
