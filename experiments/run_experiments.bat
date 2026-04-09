@echo off
echo =======================================================
echo Booting AutoHeal-J Chaos Experimentation Pipeline...
echo =======================================================
cd /d "d:\Java PROJECT\AutoHeal-J\experiments"

echo Checking Dependencies...
python -m pip install -r requirements.txt

echo.
echo Executing Pipeline Script...
python run_experiments.py

echo.
echo Pipeline Finished! You can now view dashboard.html!
pause
