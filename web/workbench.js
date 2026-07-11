"use strict";

var workbenchContent = document.getElementById("workbenchContent");
var workbenchLoading = document.getElementById("workbenchLoading");
var workbenchMonitoringPage = document.getElementById("monitoringPage");
var workbenchNavItems = document.querySelectorAll("[data-workbench-route]");
var assistantToggleButton = document.getElementById("assistantToggleButton");
var assistantDrawer = document.getElementById("assistantDrawer");
var assistantCloseButton = document.getElementById("assistantCloseButton");

function currentWorkbenchRoute() {
  var route = window.location.hash.replace(/^#\/?/, "");
  return route === "monitoring" ? route : "monitoring";
}

function mountWorkbenchPages() {
  if (workbenchMonitoringPage.parentElement !== workbenchContent) {
    workbenchContent.appendChild(workbenchMonitoringPage);
  }
}

function updateWorkbenchNavigation(route) {
  workbenchNavItems.forEach(function(item) {
    var active = item.dataset.workbenchRoute === route;
    item.classList.toggle("active", active);
    if (active) {
      item.setAttribute("aria-current", "page");
    } else {
      item.removeAttribute("aria-current");
    }
  });
}

function applyWorkbenchRoute() {
  var route = currentWorkbenchRoute();
  mountWorkbenchPages();
  document.querySelectorAll(".workbench-page").forEach(function(page) {
    page.hidden = true;
  });
  workbenchMonitoringPage.hidden = route !== "monitoring";
  updateWorkbenchNavigation(route);
  if (workbenchLoading) workbenchLoading.hidden = true;

  if (!currentUser) return;
  if (!adminToken) {
    requireAdminThenOpen("monitoring");
    return;
  }
  if (route === "monitoring" && window.activateMonitoringPage) {
    window.activateMonitoringPage();
  }
}

function navigateWorkbench(route) {
  var target = route === "monitoring" ? "#/monitoring" : "#/monitoring";
  if (window.location.hash === target) {
    applyWorkbenchRoute();
    return;
  }
  window.location.hash = target;
}

function initializeWorkbench() {
  mountWorkbenchPages();
  if (!currentUser) return;
  navigateWorkbench(currentWorkbenchRoute());
}

function openAssistantDrawer() {
  assistantDrawer.hidden = false;
  assistantToggleButton.setAttribute("aria-expanded", "true");
  messages.scrollTop = messages.scrollHeight;
  queryInput.focus();
}

function closeAssistantDrawer() {
  assistantDrawer.hidden = true;
  assistantToggleButton.setAttribute("aria-expanded", "false");
  assistantToggleButton.focus();
}

function toggleAssistantDrawer() {
  if (assistantDrawer.hidden) {
    openAssistantDrawer();
  } else {
    closeAssistantDrawer();
  }
}

window.initializeWorkbench = initializeWorkbench;
window.navigateWorkbench = navigateWorkbench;
window.openMonitoringWorkbench = function() {
  return navigateWorkbench("monitoring");
};

workbenchNavItems.forEach(function(item) {
  item.addEventListener("click", function() {
    navigateWorkbench(item.dataset.workbenchRoute);
  });
});

assistantToggleButton.addEventListener("click", toggleAssistantDrawer);
assistantCloseButton.addEventListener("click", closeAssistantDrawer);
document.addEventListener("keydown", function(event) {
  if (event.key === "Escape" && !assistantDrawer.hidden) {
    closeAssistantDrawer();
  }
});

window.addEventListener("hashchange", applyWorkbenchRoute);
initializeWorkbench();
