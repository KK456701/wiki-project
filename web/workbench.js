"use strict";

var workbenchContent = document.getElementById("workbenchContent");
var workbenchLoading = document.getElementById("workbenchLoading");
var workbenchMonitoringPage = document.getElementById("monitoringPage");
var monitoringNavItem = document.querySelector('[data-workbench-route="monitoring"]');

function mountMonitoringPage() {
  if (workbenchMonitoringPage.parentElement !== workbenchContent) {
    workbenchContent.appendChild(workbenchMonitoringPage);
  }
  workbenchMonitoringPage.hidden = false;
  if (workbenchLoading) workbenchLoading.hidden = true;
}

function initializeWorkbench() {
  mountMonitoringPage();
  if (!currentUser) return;
  requireAdminThenOpen("monitoring");
}

window.initializeWorkbench = initializeWorkbench;

monitoringNavItem.addEventListener("click", function() {
  requireAdminThenOpen("monitoring");
});

initializeWorkbench();
